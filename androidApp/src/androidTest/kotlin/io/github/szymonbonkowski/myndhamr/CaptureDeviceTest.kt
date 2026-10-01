package io.github.szymonbonkowski.myndhamr

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ar.core.ArCoreApk
import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical integration: mocks cannot establish Camera2/ARCore coexistence or real IMU delivery. */
@RunWith(AndroidJUnit4::class)
class CaptureDeviceTest {
    @Test fun twoRealCaptureCyclesProduceReplayableMetadataAndImu() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assumeTrue("Requires ARCore supported physical phone",!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk_gphone") && ArCoreApk.getInstance().checkAvailability(context).isSupported)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            repeat(3) {
                instrumentation.runOnMainSync { activity.startCapture();activity.recorder.stop() }
                await(15_000) { activity.recorder.state=="COMPLETED" || activity.recorder.state=="FAILED" }
                assertEquals("COMPLETED",activity.recorder.state)
                await(5000) { captureThreads()==0 }
            }
            repeat(3) {
                instrumentation.runOnMainSync { activity.startCapture() }
                Thread.sleep(10)
                instrumentation.runOnMainSync { activity.recorder.stop() }
                await(15_000) { activity.recorder.state=="COMPLETED" || activity.recorder.state=="FAILED" }
                assertEquals("COMPLETED",activity.recorder.state)
                await(5000) { captureThreads()==0 }
                Thread.sleep(100)
                assertEquals("COMPLETED",activity.recorder.state)
            }
            repeat(2) {
                instrumentation.runOnMainSync { activity.startCapture() }
                await(30_000) { activity.recorder.frames>=60 || activity.recorder.failure!=null }
                assertNull(activity.recorder.failure)
                assertTrue("Real frames must execute",activity.recorder.frames>=60)
                instrumentation.runOnMainSync { activity.recorder.stop() }
                await(15_000) { activity.recorder.state=="COMPLETED" || activity.recorder.state=="FAILED" }
                assertEquals("COMPLETED",activity.recorder.state)
                await(5000) { captureThreads()==0 }
                val root=activity.recorder.project!!
                val report=CaptureProject.validate(root)
                assertTrue("$report",report.valid)
                assertTrue(report.frames>=60);assertTrue(report.imuSamples>100)
                var calibrated=0; var camera=0
                CaptureProject.visitFrames(root) { frame ->
                    assertTrue(frame.intrinsics.fx>0 && frame.intrinsics.fy>0)
                    assertTrue(frame.arTimestamp.valueNs>0 && frame.cameraTimestamp.valueNs>0)
                    calibrated++; if(frame.hasCamera())camera++
                }
                assertEquals(report.frames,calibrated.toLong()); assertTrue(camera>0)
            }
            instrumentation.runOnMainSync { activity.startCapture() }
            await(30_000) { activity.recorder.frames>=60 || activity.recorder.failure!=null }
            assertNull(activity.recorder.failure)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            await(15_000) { activity.recorder.state=="INTERRUPTED" || activity.recorder.state=="FAILED" }
            assertEquals("INTERRUPTED",activity.recorder.state)
            await(5000) { captureThreads()==0 }
            assertTrue(CaptureProject.validate(activity.recorder.project!!).valid)
            instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.MainActivity").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { activity.startCapture() }
            await(30_000) { activity.recorder.frames>=60 || activity.recorder.failure!=null }
            assertNull(activity.recorder.failure)
            instrumentation.runOnMainSync { activity.recorder.stop() }
            await(15_000) { activity.recorder.state=="COMPLETED" || activity.recorder.state=="FAILED" }
            assertEquals("COMPLETED",activity.recorder.state)
            await(5000) { captureThreads()==0 }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun captureThreads():Int = Thread.getAllStackTraces().keys.count { it.isAlive && (it.name=="capture-camera" || it.name=="capture-imu") }
    private fun await(timeoutMs:Long,condition:()->Boolean) {
        val end=android.os.SystemClock.elapsedRealtime()+timeoutMs
        while(!condition() && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(100)
        assertTrue("Timed out after $timeoutMs ms",condition())
    }
}
