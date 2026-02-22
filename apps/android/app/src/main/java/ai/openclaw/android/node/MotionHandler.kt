package ai.openclaw.android.node

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import ai.openclaw.android.gateway.GatewaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

class MotionHandler(
  private val appContext: Context,
  private val json: Json,
) {

  fun handleActivity(@Suppress("UNUSED_PARAMETER") paramsJson: String?): GatewaySession.InvokeResult {
    return GatewaySession.InvokeResult.error(
      code = "MOTION_ACTIVITY_UNAVAILABLE",
      message = "MOTION_ACTIVITY_UNAVAILABLE: Android does not support historical activity recognition queries like iOS CMMotionActivityManager",
    )
  }

  suspend fun handlePedometer(paramsJson: String?): GatewaySession.InvokeResult = withContext(Dispatchers.IO) {
    val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
      ?: return@withContext GatewaySession.InvokeResult.error(
        code = "SENSOR_UNAVAILABLE",
        message = "SENSOR_UNAVAILABLE: SensorManager not available",
      )

    val stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
      ?: return@withContext GatewaySession.InvokeResult.error(
        code = "STEP_COUNTER_UNAVAILABLE",
        message = "STEP_COUNTER_UNAVAILABLE: device does not have a step counter sensor",
      )

    // Read current step count (steps since last reboot)
    val stepReading = withTimeoutOrNull(3000L) {
      suspendCancellableCoroutine<Float?> { cont ->
        val listener = object : SensorEventListener {
          override fun onSensorChanged(event: SensorEvent) {
            sensorManager.unregisterListener(this)
            cont.resume(event.values.firstOrNull())
          }

          override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
        sensorManager.registerListener(listener, stepCounterSensor, SensorManager.SENSOR_DELAY_FASTEST)
      }
    }

    if (stepReading == null) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "STEP_COUNTER_TIMEOUT",
        message = "STEP_COUNTER_TIMEOUT: could not read step counter within timeout",
      )
    }

    val steps = stepReading.toInt()
    val now = System.currentTimeMillis()
    val bootTime = now - SystemClock.elapsedRealtime()

    val result = buildJsonObject {
      put("startISO", millisToISO8601(bootTime))
      put("endISO", millisToISO8601(now))
      put("steps", steps)
      put("distanceMeters", JsonNull)
      put("floorsAscended", JsonNull)
      put("floorsDescended", JsonNull)
    }
    GatewaySession.InvokeResult.ok(result.toString())
  }

  private fun millisToISO8601(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date(millis))
  }
}
