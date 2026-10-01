package io.github.szymonbonkowski.myndhamr.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.szymonbonkowski.myndhamr.scan.v1.ImuSample

/** Raw Android device axes, m/s² and rad/s; elapsedRealtime nanoseconds, never resampled. */
internal class ImuRecorder(context:Context, private val accept:(Long,ImuSample)->Unit) {
    private val manager=context.getSystemService(SensorManager::class.java)
    private var thread:HandlerThread?=null
    @Volatile private var listener:SensorEventListener?=null
    val capabilities:List<String> get()=listOf(Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GYROSCOPE).map { type ->
        manager.getDefaultSensor(type)?.let { "sensor.$type=${it.name};vendor=${it.vendor};minDelayUs=${it.minDelay};fifo=${it.fifoMaxEventCount};resolution=${it.resolution};range=${it.maximumRange}" } ?: "sensor.$type=UNSUPPORTED"
    }
    fun start(generation:Long) {
        check(thread==null)
        val t=HandlerThread("capture-imu").also { it.start() }; thread=t
        val runListener=object:SensorEventListener {
            override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
            override fun onSensorChanged(event:SensorEvent) {
                if(listener===this) record(generation,event)
            }
        };listener=runListener
        for(type in listOf(Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GYROSCOPE)) {
            val sensor=manager.getDefaultSensor(type) ?: continue
            check(manager.registerListener(runListener,sensor,5000,0,Handler(t.looper))) { "Cannot register IMU type $type" }
        }
    }
    fun stop() { val l=listener;listener=null;if(l!=null)manager.unregisterListener(l); thread?.quitSafely(); thread=null }
    private fun record(generation:Long,event:SensorEvent) {
        accept(generation,ImuSample.newBuilder().setType(if(event.sensor.type==Sensor.TYPE_ACCELEROMETER) "ACCELEROMETER" else "GYROSCOPE")
            .setTimestamp(sourceTime(event.timestamp,"ANDROID_ELAPSED_REALTIME",SystemClock.elapsedRealtimeNanos()))
            .addAllValues(event.values.take(3).map(Float::toDouble)).setAccuracy(event.accuracy).build())
    }
}
