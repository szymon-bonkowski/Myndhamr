package io.github.szymonbonkowski.myndhamr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.ar.core.ArCoreApk
import io.github.szymonbonkowski.myndhamr.capture.CaptureRecorder
import io.github.szymonbonkowski.myndhamr.scan.v1.CaptureState

class MainActivity : ComponentActivity() {
    lateinit var recorder:CaptureRecorder; private set
    private lateinit var preview:GLSurfaceView
    private lateinit var status:TextView
    private var pendingStart=false
    private var foreground=false
    private var commandReceiver:android.content.BroadcastReceiver?=null
    private val exportDocument=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null) recorder.export(uri)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(12,40,12,24) }
        status=TextView(this).apply { text="Myndhamr Capture Recorder — ready";textSize=16f;setPadding(8,8,8,8) }
        layout.addView(status)
        preview=GLSurfaceView(this);layout.addView(preview,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        recorder=CaptureRecorder(this,preview) { message -> runOnUiThread { status.text=message } }
        fun button(label:String,action:()->Unit) { layout.addView(Button(this).apply { text=label;setOnClickListener { action() } }) }
        button("Start scan") { startCapture() }
        button("Manual keyframe") { recorder.markKeyframe() }
        button("Stop scan") { recorder.stop() }
        button("Reopen last project") { recorder.reopenLatest() }
        button("Export scan") {
            val p=recorder.project
            if(p!=null && recorder.state !in listOf("STARTING","RECORDING","STOPPING")) exportDocument.launch("${p.name}.zip")
            else status.text="Stop scanning or reopen a saved project before export"
        }
        setContentView(layout)
        // Explicit debug-only ADB commands support repeatable acceptance on the development build.
        handle(intent)
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) {
            commandReceiver=object:android.content.BroadcastReceiver() {
                override fun onReceive(context:android.content.Context,intent:Intent) { command(intent.getStringExtra("capture_command")) }
            }
            val filter=android.content.IntentFilter("io.github.szymonbonkowski.myndhamr.CAPTURE_COMMAND")
            if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(commandReceiver,filter,"android.permission.DUMP",null,android.content.Context.RECEIVER_EXPORTED)
            else { @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(commandReceiver,filter,"android.permission.DUMP",null) }
        }
    }
    fun startCapture() {
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            pendingStart=true;requestPermissions(arrayOf(Manifest.permission.CAMERA),100);return
        }
        try {
            if(ArCoreApk.getInstance().requestInstall(this,true)==ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                pendingStart=true;status.text="Install Google Play Services for AR, then start scan";return
            }
            recorder.start()
        } catch(e:Exception) { status.text="ARCore startup failed: ${e.message}" }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==100 && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED && pendingStart) { pendingStart=false;startCapture() }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);handle(intent) }
    private fun handle(intent:Intent) {
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE==0) return
        if(intent.getStringExtra("capture_command")=="start") preview.postDelayed({ if(foreground && !isFinishing && !isDestroyed)startCapture() },500)
    }
    private fun command(value:String?) {
        when(value) {
            "start" -> if(foreground)startCapture()
            "keyframe" -> recorder.markKeyframe()
            "stop" -> recorder.stop()
            "export" -> recorder.export()
            "reopen" -> recorder.reopenLatest()
        }
    }
    override fun onResume() { super.onResume();foreground=true;preview.onResume() }
    override fun onPause() {
        // Stop independent capture owners first; pause the preview after asynchronous closure.
        foreground=false
        recorder.stop(CaptureState.INTERRUPTED) { runOnUiThread { if(!foreground)preview.onPause() } }
        super.onPause()
    }
    override fun onDestroy() { commandReceiver?.let(::unregisterReceiver);recorder.destroy();super.onDestroy() }
}
