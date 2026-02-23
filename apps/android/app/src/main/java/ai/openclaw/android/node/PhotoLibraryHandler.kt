package ai.openclaw.android.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.ContextCompat
import ai.openclaw.android.gateway.GatewaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PhotoLibraryHandler(
  private val appContext: Context,
  private val json: Json,
) {

  companion object {
    private const val MAX_TOTAL_BASE64_BYTES = 340_000
    private const val MAX_PER_PHOTO_BASE64_BYTES = 300_000
    private const val DEFAULT_LIMIT = 1
    private const val MAX_LIMIT = 20
    private const val DEFAULT_MAX_WIDTH = 1600
    private const val DEFAULT_QUALITY = 0.85
  }

  suspend fun handleLatest(paramsJson: String?): GatewaySession.InvokeResult = withContext(Dispatchers.IO) {
    // Check permission
    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_IMAGES) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
    }
    if (!hasPermission) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "PERMISSION_DENIED",
        message = "PERMISSION_DENIED: photo library access not granted",
      )
    }

    // Parse params
    val params = parseParams(paramsJson)
    val limit = (params.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
    val maxWidth = (params.maxWidth ?: DEFAULT_MAX_WIDTH).coerceAtLeast(100)
    val quality = (params.quality ?: DEFAULT_QUALITY).coerceIn(0.1, 1.0)

    // Query MediaStore
    val projection = arrayOf(
      MediaStore.Images.Media._ID,
      MediaStore.Images.Media.DATE_ADDED,
      MediaStore.Images.Media.WIDTH,
      MediaStore.Images.Media.HEIGHT,
    )
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    val photos = buildJsonArray {
      var totalBase64Bytes = 0

      appContext.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        var count = 0

        while (cursor.moveToNext() && count < limit) {
          val imageId = cursor.getLong(idCol)
          val dateAdded = cursor.getLong(dateCol)
          val imageUri = android.content.ContentUris.withAppendedId(uri, imageId)

          val originalBitmap = try {
            appContext.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it) }
          } catch (_: Throwable) {
            null
          } ?: continue

          val finalEncoded = try {
            val scaled = scaleBitmap(originalBitmap, maxWidth)
            val result = fitToBudget(scaled, quality)
            if (scaled !== originalBitmap) scaled.recycle()
            result
          } finally {
            originalBitmap.recycle()
          } ?: continue

          if (totalBase64Bytes + finalEncoded.base64.length > MAX_TOTAL_BASE64_BYTES) break

          totalBase64Bytes += finalEncoded.base64.length
          count++

          add(buildJsonObject {
            put("format", "jpeg")
            put("base64", finalEncoded.base64)
            put("width", finalEncoded.width)
            put("height", finalEncoded.height)
            put("createdAt", epochSecondsToISO8601(dateAdded))
          })
        }
      }
    }

    val result = buildJsonObject { put("photos", photos) }
    GatewaySession.InvokeResult.ok(result.toString())
  }

  private data class EncodedPhoto(val base64: String, val width: Int, val height: Int)

  private fun fitToBudget(
    bitmap: Bitmap,
    initialQuality: Double,
  ): EncodedPhoto? {
    // Try compressing at current quality, then lower quality gradually
    var quality = initialQuality
    while (quality >= 0.25) {
      val base64 = compressToBase64(bitmap, (quality * 100).toInt())
      if (base64.length <= MAX_PER_PHOTO_BASE64_BYTES) {
        return EncodedPhoto(base64 = base64, width = bitmap.width, height = bitmap.height)
      }
      quality -= 0.15
    }

    // Still too large — downscale by 75% each step at minimum quality
    var current = bitmap
    for (i in 1..4) {
      val newWidth = (current.width * 0.75).toInt().coerceAtLeast(100)
      if (newWidth >= current.width) break
      val scaled = scaleBitmap(current, newWidth)
      if (current !== bitmap) current.recycle()
      current = scaled
      val base64 = compressToBase64(current, 25)
      if (base64.length <= MAX_PER_PHOTO_BASE64_BYTES) {
        val result = EncodedPhoto(base64 = base64, width = current.width, height = current.height)
        if (current !== bitmap) current.recycle()
        return result
      }
    }

    if (current !== bitmap) current.recycle()
    return null
  }

  private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int): Bitmap {
    if (bitmap.width <= maxWidth) return bitmap
    val ratio = maxWidth.toFloat() / bitmap.width
    val newHeight = (bitmap.height * ratio).toInt()
    return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
  }

  private fun compressToBase64(bitmap: Bitmap, quality: Int): String {
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
    return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
  }

  private fun epochSecondsToISO8601(epochSeconds: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date(epochSeconds * 1000))
  }

  private data class Params(val limit: Int?, val maxWidth: Int?, val quality: Double?)

  private fun parseParams(paramsJson: String?): Params {
    if (paramsJson.isNullOrBlank()) return Params(null, null, null)
    return try {
      val obj = json.parseToJsonElement(paramsJson) as? kotlinx.serialization.json.JsonObject
        ?: return Params(null, null, null)
      Params(
        limit = (obj["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull(),
        maxWidth = (obj["maxWidth"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull(),
        quality = (obj["quality"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull(),
      )
    } catch (_: Throwable) {
      Params(null, null, null)
    }
  }
}
