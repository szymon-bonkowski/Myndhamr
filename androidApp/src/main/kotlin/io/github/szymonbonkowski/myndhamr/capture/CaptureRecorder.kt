package io.github.szymonbonkowski.myndhamr.capture

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import io.github.szymonbonkowski.myndhamr.scan.v1.*
import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import io.github.szymonbonkowski.myndhamr.store.CaptureWriter
import io.github.szymonbonkowski.myndhamr.store.CaptureValidation
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal fun sourceTime(value:Long,domain:String,arrival:Long=SystemClock.elapsedRealtimeNanos()):Timestamp = Timestamp.newBuilder()
    .setValueNs(value).setClockDomain(domain).setArrivalElapsedNs(arrival).build()

/** Coordinates bounded capture producers and one disk writer; no disk work on UI/sensor/camera/GL callbacks. */
class CaptureRecorder(private val context:Context, val view:GLSurfaceView, private val report:(String)->Unit) : GLSurfaceView.Renderer {
    private val preview=CameraPreview()
    @Volatile private var engine:SharedCameraEngine?=null
    private var generation=0L
    private val gate=Any()
    private val accepting=AtomicBoolean(false)
    private val stopping=AtomicBoolean(false)
    private val keyframe=AtomicBoolean(false)
    private val executor=CaptureWorkQueue(::fail)
    private val imu=ImuRecorder(context) { token,sample -> synchronized(gate) { if(token==generation) submit { writer!!.appendImu(sample) } } }
    private var writer:CaptureWriter?=null
    private var frameId=0L
    private var lastArTimestamp=0L
    private var admission=CameraFrameAdmission()
    private var width=1; private var height=1
    @Volatile var state="IDLE"; private set
    @Volatile var project:File?=null; private set
    @Volatile var failure:String?=null; private set
    @Volatile var frames=0L; private set
    @Volatile var keyframes=0L; private set
    @Volatile var queueHighWater=0; private set
    @Volatile var tracking="NONE"; private set
    @Volatile private var exportErrors=0L
    @Volatile private var reopenErrors=0L
    @Volatile private var lastOperationFailure:String?=null
    private var lastStatus=0L
    @Volatile private var destroyed=false
    private val stopCallbacks=mutableListOf<()->Unit>()

    init { view.setEGLContextClientVersion(2); view.preserveEGLContextOnPause=true; view.setRenderer(this) }
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) { preview.create() }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int) { width=w;height=h;GLES20.glViewport(0,0,w,h); engine?.geometry(rotation(),w,h) }
    override fun onDrawFrame(gl:GL10?) { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); engine?.update() }
    private fun rotation():Int=context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation

    fun start() {
        val sessionToken=synchronized(gate) {
            if(state=="STARTING" || accepting.get() || stopping.get() || destroyed) return
            generation++; project=null;state="STARTING"; failure=null; frames=0; keyframes=0; frameId=0; lastArTimestamp=0; admission=CameraFrameAdmission(); queueHighWater=0; tracking="NONE"; keyframe.set(false)
            generation
        }
        updateStatus()
        view.queueEvent {
            try {
                check(preview.texture!=0) { "GL preview not ready" }
                val e=synchronized(gate) {
                    if(state!="STARTING" || destroyed || generation!=sessionToken) return@queueEvent
                    val token=sessionToken
                    SharedCameraEngine(context,view,preview.texture,
                        { c -> synchronized(gate) { if(token==generation)submit { writer!!.appendCamera(c) } } }, ::captureFrame, ::fail).also { engine=it }
                }
                e.open(); e.geometry(rotation(),width,height)
                val id=UUID.randomUUID().toString()
                val root=File(context.filesDir,"scans/$id.scan3d")
                val manifest=CaptureManifest.newBuilder().setFormatVersion(1).setProjectId(id)
                    .setDevice("${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}")
                    .addAllCapabilities(e.capabilities+imu.capabilities+listOf("imu.axes=Android-device;accelerometer=m/s^2;gyroscope=rad/s;timestamp=elapsedRealtimeNanos;requestedPeriodUs=5000;maxReportLatencyUs=0"))
                    .setStartedNs(SystemClock.elapsedRealtimeNanos()).setState(CaptureState.RECORDING)
                    .setPoseConvention(CaptureValidation.POSE_CONVENTION)
                    .setImageConvention(CaptureValidation.IMAGE_CONVENTION)
                    .setAppVersion("0.1.0").build()
                synchronized(gate) {
                    if(state!="STARTING" || destroyed || generation!=sessionToken || engine!==e || e.isClosing || failure!=null || stopping.get()) return@queueEvent
                    check(executor.executeControl { try { writer=CaptureProject.create(root,manifest) } catch(t:Exception) { fail("PERSISTENCE_CREATE:${t.message}") } }) { "Writer initialization was not admitted" }
                    project=root
                    accepting.set(true); state="RECORDING"
                    imu.start(generation)
                }
                event("START","Camera2 SharedCamera; manual RGB assets; depth provenance retained"); updateStatus()
            } catch(e:Exception) { fail("START:${e.javaClass.simpleName}:${e.message}") }
        }
    }
    fun markKeyframe() {
        if(accepting.get()) { keyframe.set(true); event("MANUAL_KEYFRAME_REQUEST","Will capture next tracked frame with exact Camera2 metadata and CPU image timestamp") }
    }
    private fun intrinsics(camera:com.google.ar.core.Camera):Intrinsics {
        return intrinsics(camera.imageIntrinsics,"ARCORE_CPU_IMAGE_PINHOLE;unrotated-pixels")
    }
    private fun intrinsics(i:com.google.ar.core.CameraIntrinsics,model:String):Intrinsics {
        val f=i.focalLength; val p=i.principalPoint; val d=i.imageDimensions
        return Intrinsics.newBuilder().setFx(f[0].toDouble()).setFy(f[1].toDouble()).setCx(p[0].toDouble()).setCy(p[1].toDouble())
            .setWidth(d[0]).setHeight(d[1]).setModel(model).build()
    }
    private data class PendingAsset(val path:String,val bytes:ByteArray,val encoding:String,val depthIndex:Int=-1,val confidence:Boolean=false)
    private fun captureFrame(frame:Frame,observation:CameraObservation?) {
        preview.draw(frame)
        if(!accepting.get() || frame.timestamp==lastArTimestamp) return
        if(frame.timestamp<lastArTimestamp) { fail("ARCORE_TIMESTAMP_REGRESSION"); return }
        lastArTimestamp=frame.timestamp
        when(admission.observe(frame.androidCameraTimestamp)) {
            CameraFrameAdmission.Decision.ACCEPT -> Unit
            CameraFrameAdmission.Decision.REGRESSION -> { fail("CAMERA_TIMESTAMP_REGRESSION");return }
            else -> return
        }
        val e=engine ?: return
        val camera=frame.camera; tracking=camera.trackingState.name
        val i=intrinsics(camera)
        val b=CaptureFrame.newBuilder().setFrameId(++frameId).setArTimestamp(sourceTime(frame.timestamp,"ARCORE_FRAME"))
            .setCameraTimestamp(sourceTime(frame.androidCameraTimestamp,e.clockDomain))
            .setTrackingState(TrackingState.valueOf(tracking)).setIntrinsics(i)
            .setImageFormat("YUV_420_888;packed-I420-on-keyframe").setImageWidth(i.width).setImageHeight(i.height)
        if(camera.trackingState==com.google.ar.core.TrackingState.TRACKING) {
            val m=FloatArray(16);camera.pose.toMatrix(m,0)
            b.pose=Pose.newBuilder().addAllColumnMajor(m.map(Float::toDouble))
                .setConvention(CaptureValidation.POSE_CONVENTION).build()
        } else b.failureReason="TRACKING_${camera.trackingState}:${camera.trackingFailureReason}"
        if(observation!=null) b.camera=observation
        val projection=FloatArray(16); camera.getProjectionMatrix(projection,0,0.1f,100f)
        b.addAllProjectionColumnMajor(projection.map(Float::toDouble)).setProjectionNearMeters(0.1).setProjectionFarMeters(100.0)
            .setProjectionConvention("column-major;OpenGL-NDC;display-oriented-camera;meters").setDisplayRotation(rotation())
        val assets=mutableListOf<PendingAsset>()
        var selected=false
        if(keyframe.get() && camera.trackingState==com.google.ar.core.TrackingState.TRACKING && observation!=null) {
            try {
                frame.acquireCameraImage().use { image ->
                    if(image.timestamp==frame.androidCameraTimestamp) {
                        assets+=PendingAsset("assets/rgb/${b.frameId}.i420",ImagePacking.yuv(image),"i420")
                        b.imageTimestamp=sourceTime(image.timestamp,e.clockDomain); b.keyframe=true; selected=true
                        keyframe.set(false)
                    } else event("KEYFRAME_IMAGE_ASSOCIATION_REJECTED","image=${image.timestamp};camera=${frame.androidCameraTimestamp}")
                }
            } catch(_:NotYetAvailableException) { event("KEYFRAME_IMAGE_TEMPORARILY_UNAVAILABLE","request retained") }
        }
        for(raw in listOf(true,false)) {
            val supported=if(raw)e.rawDepth else e.smoothDepth
            val d=DepthRecord.newBuilder().setSource(if(raw)DepthSource.ARCORE_RAW else DepthSource.ARCORE_SMOOTHED)
                .setUnitMeters(0.001).setAlignment("ARCORE_DEPTH_TEXTURE_VIEW;CPU-image-may-be-cropped;texture-intrinsics-scaled;axial-Z-millimeters")
                .setConfidenceAvailability(if(raw && supported)DepthAvailability.TEMPORARILY_UNAVAILABLE else DepthAvailability.UNSUPPORTED)
            if(!supported) d.availability=DepthAvailability.UNSUPPORTED
            else try {
                val image=if(raw)frame.acquireRawDepthImage16Bits() else frame.acquireDepthImage16Bits()
                image.use {
                    d.availability=DepthAvailability.AVAILABLE; d.timestamp=sourceTime(it.timestamp,"ARCORE_DEPTH")
                    d.width=it.width;d.height=it.height
                    val textureCalibration=intrinsics(camera.textureIntrinsics,"ARCORE_TEXTURE_PINHOLE")
                    val sx=it.width.toDouble()/textureCalibration.width; val sy=it.height.toDouble()/textureCalibration.height
                    d.intrinsics=textureCalibration.toBuilder().setFx(textureCalibration.fx*sx).setFy(textureCalibration.fy*sy)
                        .setCx(textureCalibration.cx*sx).setCy(textureCalibration.cy*sy).setWidth(it.width).setHeight(it.height)
                        .setModel("ARCORE_DEPTH_SCALED_TEXTURE_PINHOLE").build()
                    val sample=FloatArray(8)
                    frame.transformCoordinates2d(com.google.ar.core.Coordinates2d.IMAGE_PIXELS,
                        floatArrayOf(0f,0f,i.width.toFloat(),0f,0f,i.height.toFloat(),i.width.toFloat(),i.height.toFloat()),
                        com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,sample)
                    d.addAllCpuToDepthColumnMajor(DepthCalibration.mapping(i.width,i.height,it.width,it.height,sample))
                        .setMappingConvention("H_depthPixels_cpuImagePixels;column-vectors;column-major3x3;measured-ARCore-crop;outside-depth-bounds=no-correspondence")
                    d.detail="format=${it.format};planeLittleEndian16;zero=invalid;sourceTimestampMayRepeatForReprojection;assets=${if(selected)"stored" else "not-requested-metadata-only"}"
                    val index=b.depthCount
                    if(selected) assets+=PendingAsset("assets/depth/${b.frameId}-${if(raw)"raw" else "smooth"}.u16",ImagePacking.depth(it,2),"U16_LE;millimeters;axial-Z",index)
                    if(raw) try {
                        frame.acquireRawDepthConfidenceImage().use { confidence ->
                            check(confidence.width==it.width && confidence.height==it.height) { "Raw depth/confidence dimensions mismatch" }
                            d.confidenceTimestamp=sourceTime(confidence.timestamp,"ARCORE_DEPTH_CONFIDENCE")
                            d.confidenceAvailability=DepthAvailability.AVAILABLE
                            d.detail += ";confidence=available-U8;association=ARCore-same-frame-paired-API;original-confidence-timestamp-retained"
                            if(selected) assets+=PendingAsset("assets/confidence/${b.frameId}.u8",ImagePacking.depth(confidence,1),"U8;0-invalid;255-highest",index,true)
                        }
                    } catch(_:NotYetAvailableException) { d.detail += ";confidence=temporarily-unavailable" }
                }
            } catch(_:NotYetAvailableException) { d.availability=DepthAvailability.TEMPORARILY_UNAVAILABLE;d.detail="ARCore NotYetAvailable" }
            d.assetOmittedByPolicy = !selected && d.availability == DepthAvailability.AVAILABLE
            b.addDepth(d)
        }
        val record=b.build()
        val bytes=assets.sumOf { it.bytes.size.toLong() }
        submit(bytes) {
            val saved=record.toBuilder()
            for(a in assets) {
                val reference=writer!!.writeAsset(a.path,a.bytes,a.encoding)
                if(a.depthIndex<0) saved.rgb=reference
                else {
                    val d=saved.getDepth(a.depthIndex).toBuilder()
                    if(a.confidence)d.confidence=reference else d.depth=reference
                    saved.setDepth(a.depthIndex,d)
                }
            }
            writer!!.appendFrame(saved.build()); frames++; if(saved.keyframe)keyframes++
            if(frames % 30L == 0L) writer!!.sync()
        }
        if(SystemClock.elapsedRealtimeNanos()-lastStatus>1_000_000_000L) { lastStatus=SystemClock.elapsedRealtimeNanos();updateStatus() }
    }
    private fun submit(bytes:Long=0,task:()->Unit) {
        synchronized(gate) {
            if(!accepting.get()) return
            executor.offer(bytes,task)
            queueHighWater=maxOf(queueHighWater,executor.queueSize)
        }
    }
    private fun event(type:String,detail:String) {
        val v=SessionEvent.newBuilder().setType(type).setTimestamp(sourceTime(SystemClock.elapsedRealtimeNanos(),"APPLICATION_ELAPSED")).setDetail(detail).build()
        submit { writer!!.appendEvent(v) }
    }
    private fun fail(message:String) {
        if(failure==null) { failure=message; android.util.Log.e("MyndhamrCapture",message); try { report(message) } finally { stop(CaptureState.FAILED) } }
    }
    fun stop(finalState:CaptureState=CaptureState.COMPLETED,afterStop:(()->Unit)?=null) {
        synchronized(gate) {
            if(afterStop!=null)stopCallbacks.add(afterStop)
            if(stopping.get()) return
            if(state=="IDLE" || state=="COMPLETED" || state=="INTERRUPTED" || state=="FAILED") {
                val callbacks=stopCallbacks.toList();stopCallbacks.clear();callbacks.forEach { it() };return
            }
            event("STOP","final=$finalState;queueHighWater=$queueHighWater;failure=$failure;pendingKeyframe=${keyframe.get()};repeatedArUpdates=${admission.repeated};cameraTimestampUnavailable=${admission.unavailable}")
            stopping.set(true); accepting.set(false); state="STOPPING"
            imu.stop()
        }
        updateStatus()
        Thread({
            val done={
                check(executor.finalize {
                    try {
                        writer?.appendEvent(SessionEvent.newBuilder().setType("FINAL_DIAGNOSTICS")
                            .setTimestamp(sourceTime(SystemClock.elapsedRealtimeNanos(),"APPLICATION_ELAPSED"))
                            .setDetail("frames=$frames;keyframes=$keyframes;queueHighWater=$queueHighWater;repeatedArUpdates=${admission.repeated};cameraTimestampUnavailable=${admission.unavailable};failure=$failure;failureCallbackErrors=${executor.failureCallbackErrors}").build())
                        writer?.finish(SystemClock.elapsedRealtimeNanos(),if(failure==null)finalState else CaptureState.FAILED)
                    }
                    catch(e:Exception) { failure="FINALIZE:${e.message}" }
                    finally {
                        try { writer?.close() }
                        catch(e:Exception) { failure="CLOSE:${e.message};previous=$failure" }
                        finally { writer=null }
                    }
                    // Publish completion on the next FIFO task, after the queue releases its terminal reservation.
                    check(executor.executeControl {
                        synchronized(gate) { engine=null; stopping.set(false); state=if(failure==null)finalState.name else "FAILED" }
                        updateStatus()
                        val callbacks=synchronized(gate) { stopCallbacks.toList().also { stopCallbacks.clear() } };callbacks.forEach { it() }
                        if(destroyed)executor.shutdown()
                    }) { "Capture completion was not admitted" }
                }) { "Capture finalization was not admitted" }
            }
            engine?.close(done) ?: done()
        },"capture-stop").start()
    }
    fun export(destination:android.net.Uri?=null) {
        if(state=="STARTING" || accepting.get() || stopping.get()) { report("Stop scanning before export");return }
        val p=project ?: return report("No project selected")
        executor.executeControl {
            try {
                val output=File(context.filesDir,"exports/${p.name}-${UUID.randomUUID()}.zip");output.parentFile!!.mkdirs()
                CaptureProject.export(p,output)
                if(destination!=null) {
                    val stream=context.contentResolver.openOutputStream(destination,"w") ?: error("Cannot open selected export document")
                    stream.use { target -> output.inputStream().use { source -> source.copyTo(target,64*1024) } }
                    report("Scan exported to selected document")
                } else report("Export saved: ${output.name}")
            } catch(e:Exception) {
                exportErrors++;lastOperationFailure="EXPORT:${e.message}";updateStatus()
                report("Export failed: ${e.message}"); android.util.Log.e("MyndhamrCapture","EXPORT_FAILED",e)
            }
        }
    }
    fun reopenLatest() {
        if(state=="STARTING" || accepting.get() || stopping.get()) return
        val token=synchronized(gate) { generation }
        executor.executeControl {
            try {
                val p=File(context.filesDir,"scans").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }
                if(p==null)report("No saved project") else {
                    if(CaptureProject.readManifest(p).state==CaptureState.RECORDING) {
                        CaptureProject.recover(p).use { it.finish(SystemClock.elapsedRealtimeNanos(),CaptureState.INTERRUPTED) }
                    }
                    val result=CaptureProject.validate(p)
                    require(result.valid) { "Invalid saved project: ${result.diagnostics.joinToString()}" }
                    val savedState=requireNotNull(result.manifest).state.name
                    synchronized(gate) {
                        if(token!=generation || destroyed) return@executeControl
                        project=p;frames=result.frames;keyframes=result.keyframes
                        state=savedState;tracking="NONE";failure=null
                    }
                    updateStatus();report("Saved project: ${p.name}; $result")
                }
            } catch(e:Exception) { reopenErrors++;lastOperationFailure="REOPEN:${e.message}";updateStatus();report("Reopen failed: ${e.message}") }
        }
    }
    fun destroy() { destroyed=true;if(accepting.get() || state=="STARTING" || stopping.get())stop(CaptureState.INTERRUPTED) else executor.shutdown() }
    private fun updateStatus() {
        val value=JSONObject().put("state",state).put("project",project?.name).put("frames",frames).put("keyframes",keyframes)
            .put("tracking",tracking).put("queue",executor.queueSize).put("queueHighWater",queueHighWater).put("queuedBytes",executor.queuedBytes).put("failure",failure).put("failureCallbackErrors",executor.failureCallbackErrors).put("queueLifetimeLastFailure",executor.lastFailure)
            .put("pendingKeyframe",keyframe.get()).put("exportErrors",exportErrors).put("reopenErrors",reopenErrors).put("lastOperationFailure",lastOperationFailure)
        // Small operational status is derived, outside immutable project. Avoid disk on capture threads.
        android.os.Handler(android.os.Looper.getMainLooper()).post { report("$state | $tracking | frames=$frames | keyframes=$keyframes${failure?.let { " | $it" } ?: ""}") }
        executor.offerOptional { File(context.filesDir,"capture-status.json").writeText(value.toString()) }
    }
}
