package ai.openclaw.android.node

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.DatabaseUtils
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import ai.openclaw.android.gateway.GatewaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class CalendarHandler(
  private val appContext: Context,
  private val json: Json,
) {

  companion object {
    private const val DEFAULT_EVENT_LIMIT = 50
    private const val MAX_EVENT_LIMIT = 500
    private const val DEFAULT_DAYS_AHEAD = 7
  }

  suspend fun handleEvents(paramsJson: String?): GatewaySession.InvokeResult = withContext(Dispatchers.IO) {
    if (!hasReadPermission()) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "CALENDAR_PERMISSION_REQUIRED",
        message = "CALENDAR_PERMISSION_REQUIRED: read calendar permission not granted",
      )
    }

    val params = parseEventsParams(paramsJson)
    val now = System.currentTimeMillis()
    val startMs = params.startISO?.let { parseISO8601(it) } ?: now
    val endMs = params.endISO?.let { parseISO8601(it) }
      ?: (startMs + DEFAULT_DAYS_AHEAD.toLong() * 24 * 60 * 60 * 1000)
    val limit = (params.limit ?: DEFAULT_EVENT_LIMIT).coerceIn(1, MAX_EVENT_LIMIT)

    val events = buildJsonArray {
      val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(startMs.toString())
        .appendPath(endMs.toString())
        .build()

      val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
      )

      appContext.contentResolver.query(
        uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC",
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
        val titleCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val beginCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val endCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
        val allDayCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
        val locationCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        val calNameCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)

        var count = 0
        while (cursor.moveToNext() && count < limit) {
          count++
          add(buildJsonObject {
            put("identifier", cursor.getLong(idCol).toString())
            put("title", cursor.getString(titleCol) ?: "")
            put("startISO", milliToISO8601(cursor.getLong(beginCol)))
            put("endISO", milliToISO8601(cursor.getLong(endCol)))
            put("isAllDay", cursor.getInt(allDayCol) == 1)
            put("location", cursor.getString(locationCol) ?: "")
            put("calendarTitle", cursor.getString(calNameCol) ?: "")
          })
        }
      }
    }

    val result = buildJsonObject { put("events", events) }
    GatewaySession.InvokeResult.ok(result.toString())
  }

  suspend fun handleAdd(paramsJson: String?): GatewaySession.InvokeResult = withContext(Dispatchers.IO) {
    if (!hasWritePermission()) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "CALENDAR_PERMISSION_REQUIRED",
        message = "CALENDAR_PERMISSION_REQUIRED: write calendar permission not granted",
      )
    }

    val params = parseAddParams(paramsJson)
    if (params.title.isNullOrBlank()) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: title is required",
      )
    }
    if (params.startISO == null || params.endISO == null) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: startISO and endISO are required",
      )
    }

    val startMs = parseISO8601(params.startISO)
    val endMs = parseISO8601(params.endISO)
    if (startMs == null || endMs == null) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: invalid ISO8601 date format",
      )
    }

    // Resolve calendar ID
    val calendarId = resolveCalendarId(params.calendarId, params.calendarTitle)
    if (calendarId == null) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "CALENDAR_NOT_FOUND",
        message = "CALENDAR_NOT_FOUND: no writable calendar available",
      )
    }

    val values = ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, calendarId)
      put(CalendarContract.Events.TITLE, params.title)
      put(CalendarContract.Events.DTSTART, startMs)
      put(CalendarContract.Events.DTEND, endMs)
      put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
      if (params.isAllDay == true) {
        put(CalendarContract.Events.ALL_DAY, 1)
      }
      if (!params.location.isNullOrBlank()) {
        put(CalendarContract.Events.EVENT_LOCATION, params.location)
      }
      if (!params.notes.isNullOrBlank()) {
        put(CalendarContract.Events.DESCRIPTION, params.notes)
      }
    }

    val eventUri = try {
      appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
    } catch (e: Throwable) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "CALENDAR_INSERT_FAILED",
        message = "CALENDAR_INSERT_FAILED: ${e.message}",
      )
    }

    val eventId = eventUri?.lastPathSegment ?: "unknown"

    // Look up calendar display name
    val calendarTitle = getCalendarDisplayName(calendarId)

    val event = buildJsonObject {
      put("identifier", eventId)
      put("title", params.title)
      put("startISO", milliToISO8601(startMs))
      put("endISO", milliToISO8601(endMs))
      put("isAllDay", params.isAllDay == true)
      put("location", params.location ?: "")
      put("calendarTitle", calendarTitle)
    }

    val result = buildJsonObject { put("event", event) }
    GatewaySession.InvokeResult.ok(result.toString())
  }

  private fun resolveCalendarId(calendarId: String?, calendarTitle: String?): Long? {
    // If explicit ID provided, use it
    if (!calendarId.isNullOrBlank()) {
      return calendarId.toLongOrNull()
    }

    // If title provided, find matching writable calendar
    if (!calendarTitle.isNullOrBlank()) {
      appContext.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
        arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
        null,
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
        val nameCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        while (cursor.moveToNext()) {
          val name = cursor.getString(nameCol) ?: continue
          if (name.equals(calendarTitle, ignoreCase = true)) {
            return cursor.getLong(idCol)
          }
        }
      }
    }

    // Default: first writable calendar, prefer primary
    appContext.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
      "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
      arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
      null,
    )?.use { cursor ->
      val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
      val primaryCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
      var firstId: Long? = null
      while (cursor.moveToNext()) {
        val id = cursor.getLong(idCol)
        if (firstId == null) firstId = id
        if (cursor.getInt(primaryCol) == 1) return id
      }
      return firstId
    }

    return null
  }

  private fun getCalendarDisplayName(calendarId: Long): String {
    appContext.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
      "${CalendarContract.Calendars._ID} = ?",
      arrayOf(calendarId.toString()),
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        return cursor.getString(0) ?: ""
      }
    }
    return ""
  }

  private fun parseISO8601(iso: String): Long? {
    return try {
      java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Throwable) {
      try {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
      } catch (_: Throwable) {
        null
      }
    }
  }

  private fun milliToISO8601(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(millis))
  }

  private fun hasReadPermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

  private fun hasWritePermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

  private data class EventsParams(val startISO: String?, val endISO: String?, val limit: Int?)

  private fun parseEventsParams(paramsJson: String?): EventsParams {
    if (paramsJson.isNullOrBlank()) return EventsParams(null, null, null)
    return try {
      val obj = json.parseToJsonElement(paramsJson) as? JsonObject ?: return EventsParams(null, null, null)
      EventsParams(
        startISO = (obj["startISO"] as? JsonPrimitive)?.content,
        endISO = (obj["endISO"] as? JsonPrimitive)?.content,
        limit = (obj["limit"] as? JsonPrimitive)?.content?.toIntOrNull(),
      )
    } catch (_: Throwable) {
      EventsParams(null, null, null)
    }
  }

  private data class AddEventParams(
    val title: String?,
    val startISO: String?,
    val endISO: String?,
    val isAllDay: Boolean?,
    val location: String?,
    val notes: String?,
    val calendarId: String?,
    val calendarTitle: String?,
  )

  private fun parseAddParams(paramsJson: String?): AddEventParams {
    if (paramsJson.isNullOrBlank()) return AddEventParams(null, null, null, null, null, null, null, null)
    return try {
      val obj = json.parseToJsonElement(paramsJson) as? JsonObject
        ?: return AddEventParams(null, null, null, null, null, null, null, null)
      AddEventParams(
        title = (obj["title"] as? JsonPrimitive)?.content,
        startISO = (obj["startISO"] as? JsonPrimitive)?.content,
        endISO = (obj["endISO"] as? JsonPrimitive)?.content,
        isAllDay = (obj["isAllDay"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
        location = (obj["location"] as? JsonPrimitive)?.content,
        notes = (obj["notes"] as? JsonPrimitive)?.content,
        calendarId = (obj["calendarId"] as? JsonPrimitive)?.content,
        calendarTitle = (obj["calendarTitle"] as? JsonPrimitive)?.content,
      )
    } catch (_: Throwable) {
      AddEventParams(null, null, null, null, null, null, null, null)
    }
  }
}
