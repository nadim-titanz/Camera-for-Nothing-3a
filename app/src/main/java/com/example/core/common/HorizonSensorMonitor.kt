package com.example.core.common

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class HorizonSensorMonitor(context: Context) {

  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

  fun tiltDegreesFlow(): Flow<Float> = callbackFlow {
    val manager = sensorManager
    val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    if (manager == null || accelerometer == null) {
      trySend(0f)
      close()
      return@callbackFlow
    }

    var smoothedAngle = 0f
    val listener =
      object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          val x = event.values[0]
          val y = event.values[1]
          val rawAngle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
          // Low-pass filter to keep horizon line responsive yet jitter-free
          smoothedAngle = smoothedAngle * 0.8f + rawAngle * 0.2f
          trySend(smoothedAngle)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
      }

    manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
    awaitClose { manager.unregisterListener(listener) }
  }
}
