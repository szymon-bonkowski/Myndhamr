package io.github.szymonbonkowski.myndhamr.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import io.github.szymonbonkowski.myndhamr.scan.v1.CameraObservation
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One camera owner through ARCore SharedCamera. Camera callback thread owns Camera2; GL owns update. */
internal class SharedCameraEngine(
    private val context:Context,
    private val view:GLSurfaceView,
    private val texture:Int,
    private val observation:(CameraObservation)->Unit,
    private val onFrame:(Frame,CameraObservation?)->Unit,
    private val onFailure:(String)->Unit
) {
    private val closing=AtomicBoolean(false)
    val isClosing:Boolean get()=closing.get()
    private val thread=HandlerThread("capture-camera").also { it.start() }
    private val handler=Handler(thread.looper)
    private val manager=context.getSystemService(CameraManager::class.java)
    private val results=ConcurrentHashMap<Long,CameraObservation>()
    private var device:CameraDevice?=null
    private var capture:CameraCaptureSession?=null
    @Volatile private var closeCompletion:(()->Unit)?=null
    private val closed=AtomicBoolean(false)
    private val lifecycle=Any()
    private var opening=false
    @Volatile private var active=false
    lateinit var session:Session; private set
    lateinit var cameraId:String; private set
    var clockDomain="CAMERA_UNKNOWN"; private set
    var rawDepth=false; private set
    var smoothDepth=false; private set
    var capabilities:List<String> = emptyList(); private set

    @SuppressLint("MissingPermission")
    fun open() = synchronized(lifecycle) {
        if(closing.get()) return@synchronized
        check(ArCoreApk.getInstance().checkAvailability(context).isSupported) { "ARCore unsupported or unavailable" }
        session=Session(context,EnumSet.of(Session.Feature.SHARED_CAMERA))
        rawDepth=session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        smoothDepth=session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val config=session.config.apply {
            focusMode=Config.FocusMode.AUTO
            updateMode=Config.UpdateMode.LATEST_CAMERA_IMAGE
            depthMode=when { smoothDepth -> Config.DepthMode.AUTOMATIC; rawDepth -> Config.DepthMode.RAW_DEPTH_ONLY; else -> Config.DepthMode.DISABLED }
        }; session.configure(config)
        cameraId=session.cameraConfig.cameraId
        val c=manager.getCameraCharacteristics(cameraId)
        val source=c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]
        clockDomain=if(source==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "CAMERA_REALTIME" else "CAMERA_UNKNOWN"
        val arVersion=context.packageManager.getPackageInfo("com.google.ar.core",0).versionName
        capabilities=listOf("manufacturer=${Build.MANUFACTURER}","model=${Build.MODEL}","android=${Build.VERSION.RELEASE}","api=${Build.VERSION.SDK_INT}",
            "arcore.runtime=$arVersion","arcore.sdk=1.56.0","camera.id=$cameraId","camera.timestampSource=$source;$clockDomain",
            "camera.imageSize=${session.cameraConfig.imageSize}","camera.textureSize=${session.cameraConfig.textureSize}","camera.fps=${session.cameraConfig.fpsRange}",
            "camera.orientation=${c[CameraCharacteristics.SENSOR_ORIENTATION]}","camera.hardwareLevel=${c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]}",
            "camera.activeArray=${c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]}","camera.physicalIds=${if(Build.VERSION.SDK_INT>=28)c.physicalCameraIds else emptySet<String>()}",
            "camera.intrinsicCalibration=${c[CameraCharacteristics.LENS_INTRINSIC_CALIBRATION]?.joinToString()}","camera.distortion=${if(Build.VERSION.SDK_INT>=28)c[CameraCharacteristics.LENS_DISTORTION]?.joinToString() else "unavailable-api"}",
            "depth.raw.supported=$rawDepth","depth.automatic.supported=$smoothDepth","depth.config=${config.depthMode}",
            "capture.rgb=manual-keyframes-only;raw-YUV-I420;metadata-every-fresh-AR-frame","depth.assets=manual-keyframes-only;metadata-every-fresh-AR-frame")
        session.setCameraTextureName(texture)
        val wrapped=session.sharedCamera.createARDeviceStateCallback(object:CameraDevice.StateCallback() {
            override fun onOpened(camera:CameraDevice) {
                opening=false;device=camera
                if(closing.get()) { camera.close(); return }
                try {
                    val surfaces=session.sharedCamera.arCoreSurfaces
                    val request=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { surfaces.forEach(::addTarget) }.build()
                    val state=object:CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s:CameraCaptureSession) {
                            if(closing.get()) { s.close(); return }; capture=s
                            try { s.setRepeatingRequest(request,callback,handler) } catch(e:Exception) { fail("Camera repeating request",e) }
                        }
                        override fun onActive(s:CameraCaptureSession) {
                            synchronized(lifecycle) {
                                if(!closing.get() && !active) try {
                                    session.resume(); session.sharedCamera.setCaptureCallback(callback,handler); active=true
                                } catch(e:Exception) { fail("ARCore resume",e) }
                            }
                        }
                        override fun onConfigureFailed(s:CameraCaptureSession) { if(!closing.get())onFailure("CAMERA_CONFIGURATION_FAILED") }
                    }
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(surfaces,session.sharedCamera.createARSessionStateCallback(state,handler),handler)
                } catch(e:Exception) { fail("Camera configure",e) }
            }
            override fun onClosed(camera:CameraDevice) { if(closing.get()) completeClose() }
            override fun onDisconnected(camera:CameraDevice) { camera.close(); if(!closing.get()) onFailure("CAMERA_DISCONNECTED") }
            override fun onError(camera:CameraDevice,error:Int) { camera.close(); if(!closing.get()) onFailure("CAMERA_ERROR_$error") }
        },handler)
        opening=true
        try { manager.openCamera(cameraId,wrapped,handler) }
        catch(e:Exception) { opening=false;throw e }
    }
    private val callback=object:CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s:CameraCaptureSession,request:CaptureRequest,result:TotalCaptureResult) {
            if(closing.get()) return
            val timestamp=result[CaptureResult.SENSOR_TIMESTAMP] ?: return onFailure("CAMERA_SENSOR_TIMESTAMP_MISSING")
            val b=CameraObservation.newBuilder().setTimestamp(sourceTime(timestamp,clockDomain)).setFrameNumber(result.frameNumber)
                .setLogicalCameraId(cameraId).setTimestampSource(clockDomain)
            result[CaptureResult.SENSOR_EXPOSURE_TIME]?.let(b::setExposureNs)
            result[CaptureResult.SENSOR_SENSITIVITY]?.let(b::setIso)
            result[CaptureResult.LENS_FOCAL_LENGTH]?.let(b::setFocalMm)
            result[CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW]?.let(b::setRollingShutterNs)
            if(Build.VERSION.SDK_INT>=29) result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]?.let(b::setPhysicalCameraId)
            result[CaptureResult.LENS_INTRINSIC_CALIBRATION]?.let { b.addAllIntrinsicCalibration(it.map(Float::toDouble)) }
            if(Build.VERSION.SDK_INT>=28) result[CaptureResult.LENS_DISTORTION]?.let { b.addAllLensDistortion(it.map(Float::toDouble)) }
            result[CaptureResult.SCALER_CROP_REGION]?.let { b.addAllCropRegion(listOf(it.left,it.top,it.right,it.bottom)) }
            result[CaptureResult.COLOR_CORRECTION_GAINS]?.let { b.addAllColorGains(listOf(it.red,it.greenEven,it.greenOdd,it.blue).map(Float::toDouble)) }
            result[CaptureResult.LENS_POSE_TRANSLATION]?.let { b.addAllLensPoseTranslation(it.map(Float::toDouble)) }
            result[CaptureResult.LENS_POSE_ROTATION]?.let { b.addAllLensPoseRotation(it.map(Float::toDouble)) }
            manager.getCameraCharacteristics(cameraId)[CameraCharacteristics.SENSOR_ORIENTATION]?.let(b::setSensorOrientationDegrees)
            val value=b.build()
            // ARCore may invoke callbacks once while switching repeating ownership. Preserve unique observations.
            if(results.putIfAbsent(timestamp,value)==null) observation(value)
            if(results.size>120) results.keys.sorted().take(results.size-120).forEach(results::remove)
        }
        override fun onCaptureFailed(s:CameraCaptureSession,request:CaptureRequest,failure:CaptureFailure) {
            if(!closing.get()) onFailure("CAMERA_CAPTURE_FAILED reason=${failure.reason};frame=${failure.frameNumber}")
        }
    }
    fun update() = synchronized(lifecycle) {
        if(!active || closing.get()) return@synchronized
        try {
            val frame=session.update()
            if(frame.timestamp!=0L) onFrame(frame,results[frame.androidCameraTimestamp])
        } catch(e:Exception) { fail("ARCore update",e) }
    }
    fun geometry(rotation:Int,width:Int,height:Int) = synchronized(lifecycle) { if(::session.isInitialized && !closing.get() && !closed.get()) session.setDisplayGeometry(rotation,width,height) }
    /** Called on GL after update exits. Queued Camera2 shutdown precedes completion; no UI waiting. */
    fun close(completed:()->Unit) {
        synchronized(lifecycle) {
            if(closing.get()) return
            closeCompletion=completed
            closing.set(true)
            if(active) { active=false; try { session.pause() } catch(e:Exception) { onFailure("ARCORE_PAUSE_FAILED:${e.message}") } }
        }
        handler.post {
            try { capture?.close(); device?.close() } catch(e:Exception) { onFailure("CAMERA_CLOSE_FAILED:${e.message}") }
            if(device==null && !opening) completeClose()
            // Complete shutdown even if a device vendor never delivers onClosed. Mark the failure explicitly.
            handler.postDelayed({ if(!closed.get()) { onFailure("CAMERA_CLOSE_TIMEOUT");completeClose() } },5000)
        }
    }
    private fun completeClose() {
        if(!closed.compareAndSet(false,true)) return
        try { synchronized(lifecycle) { if(::session.isInitialized) session.close() } } catch(e:Exception) { onFailure("ARCORE_CLOSE_FAILED:${e.message}") }
        finally { results.clear();device=null;capture=null;closeCompletion?.invoke();thread.quitSafely() }
    }

    private fun fail(stage:String,e:Exception) { onFailure("$stage:${e.javaClass.simpleName}:${e.message}") }
}
