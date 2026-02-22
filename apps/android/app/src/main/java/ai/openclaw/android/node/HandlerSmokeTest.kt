package ai.openclaw.android.node

import android.content.Context
import android.util.Log
import ai.openclaw.android.gateway.GatewaySession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Smoke test for all invoke handlers. Debug builds only.
 * Results logged to Logcat tag "HandlerSmokeTest".
 */
object HandlerSmokeTest {
  private const val TAG = "HandlerSmokeTest"
  private val json = Json { ignoreUnknownKeys = true }

  fun run(context: Context) {
    Thread {
      Log.i(TAG, "=== Starting Handler Smoke Tests ===")

      // Device Status
      test("device.status") {
        DeviceStatusHandler(context).handleStatus()
      }
      test("device.info") {
        DeviceStatusHandler(context).handleInfo()
      }

      // Contacts
      test("contacts.search (all)") {
        runBlocking { ContactsHandler(context, json).handleSearch(null) }
      }
      test("contacts.search (query=John)") {
        runBlocking { ContactsHandler(context, json).handleSearch("""{"query":"John","limit":5}""") }
      }
      test("contacts.add") {
        runBlocking { ContactsHandler(context, json).handleAdd("""{"givenName":"Smoke","familyName":"Test","phoneNumbers":["+15559999999"]}""") }
      }

      // Calendar
      test("calendar.events") {
        runBlocking { CalendarHandler(context, json).handleEvents(null) }
      }
      test("calendar.add") {
        runBlocking { CalendarHandler(context, json).handleAdd("""{"title":"SmokeTest","startISO":"2026-02-22T20:00:00Z","endISO":"2026-02-22T21:00:00Z"}""") }
      }

      // Photos
      test("photos.latest") {
        runBlocking { PhotoLibraryHandler(context, json).handleLatest("""{"limit":1}""") }
      }

      // Motion
      test("motion.pedometer") {
        runBlocking { MotionHandler(context, json).handlePedometer(null) }
      }
      test("motion.activity (expect unavailable)") {
        runBlocking { MotionHandler(context, json).handleActivity(null) }
      }

      Log.i(TAG, "=== Smoke Tests Complete ===")
    }.start()
  }

  private fun test(name: String, block: () -> GatewaySession.InvokeResult) {
    try {
      val result = block()
      val payload = result.payloadJson ?: result.error?.let { "${it.code}: ${it.message}" } ?: "null"
      val preview = if (payload.length > 300) payload.take(300) + "..." else payload
      if (result.ok) {
        Log.i(TAG, "✅ $name → $preview")
      } else {
        Log.w(TAG, "⚠️ $name → $preview")
      }
    } catch (e: Exception) {
      Log.e(TAG, "❌ $name → EXCEPTION: ${e.message}", e)
    }
  }
}
