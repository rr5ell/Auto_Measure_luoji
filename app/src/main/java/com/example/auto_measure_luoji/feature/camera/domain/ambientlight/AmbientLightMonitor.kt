package com.example.auto_measure_luoji.feature.camera.domain.ambientlight

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread

/** 只读取环境光，不读取图像、不控制相机。生命周期方法和回调均在主线程执行。 */
class AmbientLightMonitor(
    private val sensorManager: SensorManager?,
    private val onReading: (AmbientLightReading) -> Unit,
) : SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var pending: AmbientLightReading? = null
    private var published: AmbientLightReading? = null
    private val publishLatest = Runnable {
        val reading = pending
        pending = null
        if (started && reading != null) publish(reading)
    }

    @MainThread
    fun start() {
        if (started) return
        started = true
        publish(AmbientLightReading(AmbientLightReading.Status.WAITING))
        try {
            val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
            if (sensor == null) {
                publish(AmbientLightReading(AmbientLightReading.Status.UNSUPPORTED))
            } else {
                Log.i(TAG, "TYPE_LIGHT sensor=${sensor.name} maxRange=${sensor.maximumRange} lx resolution=${sensor.resolution} lx")
                if (!sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)) {
                    publish(AmbientLightReading(AmbientLightReading.Status.FAILED))
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "光线传感器监听失败", error)
            publish(AmbientLightReading(AmbientLightReading.Status.FAILED))
        }
    }

    @MainThread
    fun stop() {
        started = false
        sensorManager?.unregisterListener(this)
        handler.removeCallbacks(publishLatest)
        pending = null
        publish(AmbientLightReading(AmbientLightReading.Status.PAUSED))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started || event.sensor.type != Sensor.TYPE_LIGHT) return
        // 合并窗口内事件并保留最后一次读数，避免丢弃变化型传感器的最终值。
        if (pending == null) handler.postDelayed(publishLatest, 500L)
        pending = AmbientLightReading.fromLux(event.values.firstOrNull())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publish(reading: AmbientLightReading) {
        if (published == reading) return
        published = reading
        onReading(reading)
        Log.i(TAG, "TYPE_LIGHT lux=${reading.lux} status=${reading.status} ${reading.panelText}")
    }

    private companion object { const val TAG = "AmbientLight" }
}
