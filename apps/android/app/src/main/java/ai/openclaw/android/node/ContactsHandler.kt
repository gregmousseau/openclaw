package ai.openclaw.android.node

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import ai.openclaw.android.gateway.GatewaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ContactsHandler(
  private val appContext: Context,
  private val json: Json,
) {

  companion object {
    private const val DEFAULT_LIMIT = 25
    private const val MAX_LIMIT = 200
  }

  suspend fun handleSearch(paramsJson: String?): GatewaySession.InvokeResult = withContext(Dispatchers.IO) {
    if (!hasReadPermission()) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "CONTACTS_PERMISSION_REQUIRED",
        message = "CONTACTS_PERMISSION_REQUIRED: read contacts permission not granted",
      )
    }

    val params = parseSearchParams(paramsJson)
    val query = params.first
    val limit = (params.second ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    val contacts = buildJsonArray {
      val contactIds = mutableListOf<Pair<String, String>>() // id, displayName

      if (query != null) {
        val filterUri = ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon()
          .appendPath(query).build()
        appContext.contentResolver.query(
          filterUri,
          arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
          null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
          val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
          while (cursor.moveToNext() && contactIds.size < limit) {
            val id = cursor.getString(idCol) ?: continue
            val name = cursor.getString(nameCol) ?: ""
            contactIds.add(id to name)
          }
        }
      } else {
        appContext.contentResolver.query(
          ContactsContract.Contacts.CONTENT_URI,
          arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
          null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
          val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
          while (cursor.moveToNext() && contactIds.size < limit) {
            val id = cursor.getString(idCol) ?: continue
            val name = cursor.getString(nameCol) ?: ""
            contactIds.add(id to name)
          }
        }
      }

      for ((id, displayName) in contactIds) {
        add(buildContactJson(id, displayName))
      }
    }

    val result = buildJsonObject { put("contacts", contacts) }
    GatewaySession.InvokeResult.ok(result.toString())
  }

  suspend fun handleAdd(paramsJson: String?): GatewaySession.InvokeResult = withContext(Dispatchers.IO) {
    if (!hasWritePermission()) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "CONTACTS_PERMISSION_REQUIRED",
        message = "CONTACTS_PERMISSION_REQUIRED: write contacts permission not granted",
      )
    }

    val params = parseAddParams(paramsJson)

    // Check for existing contact by phone or email
    val existingId = findExistingContact(params.phoneNumbers, params.emails)
    if (existingId != null) {
      val displayName = getContactDisplayName(existingId)
      val contact = buildContactJson(existingId, displayName)
      val result = buildJsonObject { put("contact", contact) }
      return@withContext GatewaySession.InvokeResult.ok(result.toString())
    }

    // Insert new contact
    val ops = ArrayList<ContentProviderOperation>()

    // RawContact
    ops.add(
      ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
        .build()
    )

    // StructuredName
    val hasName = !params.givenName.isNullOrBlank() || !params.familyName.isNullOrBlank() || !params.displayName.isNullOrBlank()
    if (hasName) {
      val nameOp = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
      if (!params.displayName.isNullOrBlank()) {
        nameOp.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, params.displayName)
      }
      if (!params.givenName.isNullOrBlank()) {
        nameOp.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, params.givenName)
      }
      if (!params.familyName.isNullOrBlank()) {
        nameOp.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, params.familyName)
      }
      ops.add(nameOp.build())
    }

    // Organization
    if (!params.organizationName.isNullOrBlank()) {
      ops.add(
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
          .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
          .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
          .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, params.organizationName)
          .build()
      )
    }

    // Phone numbers
    for (phone in params.phoneNumbers) {
      ops.add(
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
          .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
          .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
          .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
          .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
          .build()
      )
    }

    // Emails
    for (email in params.emails) {
      ops.add(
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
          .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
          .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
          .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
          .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
          .build()
      )
    }

    val results = try {
      appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    } catch (e: Throwable) {
      return@withContext GatewaySession.InvokeResult.error(
        code = "CONTACTS_INSERT_FAILED",
        message = "CONTACTS_INSERT_FAILED: ${e.message}",
      )
    }

    // Get the new raw contact ID and look up the aggregated contact ID
    val rawContactUri = results[0].uri
    val rawContactId = rawContactUri?.lastPathSegment
    val contactId = if (rawContactId != null) {
      lookupContactIdFromRawContact(rawContactId)
    } else null

    if (contactId != null) {
      val displayName = getContactDisplayName(contactId)
      val contact = buildContactJson(contactId, displayName)
      val result = buildJsonObject { put("contact", contact) }
      GatewaySession.InvokeResult.ok(result.toString())
    } else {
      val result = buildJsonObject { put("contact", buildJsonObject { put("identifier", rawContactId ?: "unknown") }) }
      GatewaySession.InvokeResult.ok(result.toString())
    }
  }

  private fun buildContactJson(contactId: String, displayName: String) = buildJsonObject {
    put("identifier", contactId)
    put("displayName", displayName)

    // Structured name details
    appContext.contentResolver.query(
      ContactsContract.Data.CONTENT_URI,
      arrayOf(
        ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
        ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
      ),
      "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
      arrayOf(contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        put("givenName", cursor.getString(0) ?: "")
        put("familyName", cursor.getString(1) ?: "")
      } else {
        put("givenName", "")
        put("familyName", "")
      }
    } ?: run {
      put("givenName", "")
      put("familyName", "")
    }

    // Organization
    appContext.contentResolver.query(
      ContactsContract.Data.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
      "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
      arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
      null,
    )?.use { cursor ->
      put("organizationName", if (cursor.moveToFirst()) cursor.getString(0) ?: "" else "")
    } ?: put("organizationName", "")

    // Phone numbers
    putJsonArray("phoneNumbers") {
      appContext.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(contactId),
        null,
      )?.use { cursor ->
        val numCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cursor.moveToNext()) {
          val number = cursor.getString(numCol)
          if (!number.isNullOrBlank()) add(JsonPrimitive(number))
        }
      }
    }

    // Emails
    putJsonArray("emails") {
      appContext.contentResolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
        arrayOf(contactId),
        null,
      )?.use { cursor ->
        val emailCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
        while (cursor.moveToNext()) {
          val email = cursor.getString(emailCol)
          if (!email.isNullOrBlank()) add(JsonPrimitive(email))
        }
      }
    }
  }

  private fun findExistingContact(phoneNumbers: List<String>, emails: List<String>): String? {
    for (phone in phoneNumbers) {
      val uri = android.net.Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        android.net.Uri.encode(phone),
      )
      appContext.contentResolver.query(
        uri,
        arrayOf(ContactsContract.PhoneLookup._ID),
        null, null, null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) {
          return cursor.getString(0)
        }
      }
    }

    for (email in emails) {
      val uri = android.net.Uri.withAppendedPath(
        ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
        android.net.Uri.encode(email),
      )
      appContext.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID),
        null, null, null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) {
          return cursor.getString(0)
        }
      }
    }

    return null
  }

  private fun lookupContactIdFromRawContact(rawContactId: String): String? {
    appContext.contentResolver.query(
      ContactsContract.RawContacts.CONTENT_URI,
      arrayOf(ContactsContract.RawContacts.CONTACT_ID),
      "${ContactsContract.RawContacts._ID} = ?",
      arrayOf(rawContactId),
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        return cursor.getString(0)
      }
    }
    return null
  }

  private fun getContactDisplayName(contactId: String): String {
    appContext.contentResolver.query(
      ContactsContract.Contacts.CONTENT_URI,
      arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
      "${ContactsContract.Contacts._ID} = ?",
      arrayOf(contactId),
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        return cursor.getString(0) ?: ""
      }
    }
    return ""
  }

  private fun hasReadPermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

  private fun hasWritePermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

  private fun parseSearchParams(paramsJson: String?): Pair<String?, Int?> {
    if (paramsJson.isNullOrBlank()) return null to null
    return try {
      val obj = json.parseToJsonElement(paramsJson) as? JsonObject ?: return null to null
      val query = (obj["query"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
      val limit = (obj["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
      query to limit
    } catch (_: Throwable) {
      null to null
    }
  }

  private data class AddParams(
    val givenName: String?,
    val familyName: String?,
    val organizationName: String?,
    val displayName: String?,
    val phoneNumbers: List<String>,
    val emails: List<String>,
  )

  private fun parseAddParams(paramsJson: String?): AddParams {
    if (paramsJson.isNullOrBlank()) return AddParams(null, null, null, null, emptyList(), emptyList())
    return try {
      val obj = json.parseToJsonElement(paramsJson) as? JsonObject
        ?: return AddParams(null, null, null, null, emptyList(), emptyList())
      AddParams(
        givenName = (obj["givenName"] as? JsonPrimitive)?.content,
        familyName = (obj["familyName"] as? JsonPrimitive)?.content,
        organizationName = (obj["organizationName"] as? JsonPrimitive)?.content,
        displayName = (obj["displayName"] as? JsonPrimitive)?.content,
        phoneNumbers = try {
          obj["phoneNumbers"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        } catch (_: Throwable) { emptyList() },
        emails = try {
          obj["emails"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        } catch (_: Throwable) { emptyList() },
      )
    } catch (_: Throwable) {
      AddParams(null, null, null, null, emptyList(), emptyList())
    }
  }
}
