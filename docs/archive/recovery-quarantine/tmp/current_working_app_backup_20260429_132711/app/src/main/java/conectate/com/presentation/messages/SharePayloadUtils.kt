package conectate.com.presentation.messages

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

data class DeviceContactPayload(
    val name: String,
    val phone: String,
    val email: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList()
)

fun hasLocationPermission(context: Context): Boolean {
    val fineGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fineGranted || coarseGranted
}

@SuppressLint("MissingPermission")
fun getBestLastKnownLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    return providers
        .mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }
        .maxByOrNull { it.time }
}

fun formatCoordinate(value: Double): String {
    return String.format(Locale.US, "%.6f", value)
}

fun resolvePickedContact(context: Context, uri: Uri): DeviceContactPayload? {
    val resolver = context.contentResolver

    // Case 1: URI already points to a phone row.
    val directPhonePayload = runCatching<DeviceContactPayload?> {
        resolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0).orEmpty().trim()
                val number = cursor.getString(1).orEmpty().trim()
                val contactId = cursor.getString(2).orEmpty().trim()
                val normalized = normalizePhone(number)
                val phones = buildList {
                    if (normalized.isNotBlank()) add(normalized)
                    if (contactId.isNotBlank()) {
                        queryContactPhones(resolver, contactId).forEach { value ->
                            if (value.isNotBlank() && !contains(value)) add(value)
                        }
                    }
                }
                val emails = if (contactId.isNotBlank()) queryContactEmails(resolver, contactId) else emptyList()
                if (name.isNotBlank() && (phones.isNotEmpty() || emails.isNotEmpty())) {
                    return@use DeviceContactPayload(
                        name = name,
                        phone = phones.firstOrNull().orEmpty(),
                        email = emails.firstOrNull(),
                        phones = phones,
                        emails = emails
                    )
                }
            }
            null
        }
        null
    }.getOrNull()
    if (directPhonePayload != null) return directPhonePayload

    // Case 2: URI points to a contact; fetch first available number.
    val contactProjection = arrayOf(
        ContactsContract.Contacts._ID,
        ContactsContract.Contacts.DISPLAY_NAME,
        ContactsContract.Contacts.HAS_PHONE_NUMBER
    )

    val contact = runCatching {
        resolver.query(uri, contactProjection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getString(0).orEmpty().trim()
            val name = cursor.getString(1).orEmpty().trim()
            val hasPhone = cursor.getInt(2) > 0
            Triple(id, name, hasPhone)
        }
    }.getOrNull() ?: return null

    val contactId = contact.first
    val contactName = contact.second
    if (contactId.isBlank() || contactName.isBlank() || !contact.third) return null

    val phones = queryContactPhones(resolver, contactId)
    val emails = queryContactEmails(resolver, contactId)
    if (phones.isEmpty() && emails.isEmpty()) return null

    return DeviceContactPayload(
        name = contactName,
        phone = phones.firstOrNull().orEmpty(),
        email = emails.firstOrNull(),
        phones = phones,
        emails = emails
    )
}

private fun normalizePhone(raw: String): String {
    val cleaned = raw.trim()
    if (cleaned.isBlank()) return ""
    val compact = cleaned.replace(Regex("[^0-9+]"), "")
    return compact.ifBlank { cleaned }
}

private fun normalizeEmail(raw: String): String {
    return raw.trim().lowercase(Locale.US)
}

private fun queryContactPhones(
    resolver: android.content.ContentResolver,
    contactId: String
): List<String> {
    val phoneProjection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
    val phones = runCatching {
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            phoneProjection,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(contactId),
            null
        )?.use { phoneCursor ->
            buildList {
                while (phoneCursor.moveToNext()) {
                    val normalized = normalizePhone(phoneCursor.getString(0).orEmpty())
                    if (normalized.isNotBlank() && !contains(normalized)) add(normalized)
                }
            }
        }
    }.getOrNull()
    return phones.orEmpty()
}

private fun queryContactEmails(
    resolver: android.content.ContentResolver,
    contactId: String
): List<String> {
    val emailProjection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS)
    val emails = runCatching {
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            emailProjection,
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?",
            arrayOf(contactId),
            null
        )?.use { emailCursor ->
            buildList {
                while (emailCursor.moveToNext()) {
                    val normalized = normalizeEmail(emailCursor.getString(0).orEmpty())
                    if (normalized.isNotBlank() && !contains(normalized)) add(normalized)
                }
            }
        }
    }.getOrNull()
    return emails.orEmpty()
}
