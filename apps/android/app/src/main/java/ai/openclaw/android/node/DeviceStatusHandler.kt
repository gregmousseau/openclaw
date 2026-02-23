package ai.openclaw.android.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import ai.openclaw.android.gateway.GatewaySession
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

class DeviceStatusHandler(
  private val appContext: Context,
) {

  fun handleStatus(): GatewaySession.InvokeResult {
    val json = buildJsonObject {
      // Battery
      putJsonObject("battery") {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0) / 100.0
        put("level", level)
        val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
          ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val state = when (status) {
          BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
          BatteryManager.BATTERY_STATUS_FULL -> "full"
          else -> "unplugged"
        }
        put("state", state)
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        put("lowPowerModeEnabled", pm?.isPowerSaveMode == true)
      }

      // Thermal
      putJsonObject("thermal") {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          when (pm?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "nominal"
            PowerManager.THERMAL_STATUS_MODERATE -> "fair"
            PowerManager.THERMAL_STATUS_SEVERE -> "serious"
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
            else -> "nominal"
          }
        } else {
          "nominal"
        }
        put("state", thermalState)
      }

      // Storage
      putJsonObject("storage") {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes
        put("totalBytes", totalBytes)
        put("freeBytes", freeBytes)
        put("usedBytes", totalBytes - freeBytes)
      }

      // Network
      putJsonObject("network") {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps != null) {
          val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          put("status", if (hasInternet) "satisfied" else "unsatisfied")
          put("isExpensive", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
          put("isConstrained", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
          putJsonArray("interfaces") {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(JsonPrimitive("wifi"))
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(JsonPrimitive("cellular"))
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(JsonPrimitive("wired"))
          }
        } else {
          put("status", "unsatisfied")
          put("isExpensive", false)
          put("isConstrained", false)
          putJsonArray("interfaces") {}
        }
      }

      // Uptime
      put("uptimeSeconds", SystemClock.elapsedRealtime() / 1000.0)
    }
    return GatewaySession.InvokeResult.ok(json.toString())
  }

  fun handleInfo(): GatewaySession.InvokeResult {
    val json = buildJsonObject {
      put("deviceName", Build.MODEL)
      put("modelIdentifier", Build.MODEL)
      put("systemName", "Android")
      put("systemVersion", Build.VERSION.RELEASE)
      try {
        val pInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        put("appVersion", pInfo.versionName ?: "unknown")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          pInfo.longVersionCode.toString()
        } else {
          @Suppress("DEPRECATION")
          pInfo.versionCode.toString()
        }
        put("appBuild", versionCode)
      } catch (_: Throwable) {
        put("appVersion", "unknown")
        put("appBuild", "unknown")
      }
      put("locale", Locale.getDefault().toString())
    }
    return GatewaySession.InvokeResult.ok(json.toString())
  }
}
