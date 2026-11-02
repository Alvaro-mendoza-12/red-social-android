package conectate.com.presentation.messages

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.Parcelable
import android.provider.ContactsContract
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContract
import conectate.com.util.IncomingSharePayload
import conectate.com.util.IncomingSharedItem
import conectate.com.util.MAX_INCOMING_SHARE_ITEMS
import conectate.com.util.canonicalizeMediaContentType
import conectate.com.util.classifyIncomingShareKind
import conectate.com.util.guessMimeTypeFromFileName
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class DeviceContactPayload(
    val name: String,
    val phone: String,
    val email: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList()
)

class PickPhoneContactContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

private const val EXTRA_CAPTURED_SHARE_ID = "conectate.capture.share_id"
private const val EXTRA_CAPTURED_SHARE_RECEIVED_AT = "conectate.capture.received_at"
private const val EXTRA_CAPTURED_SHARE_MIME_TYPES = "conectate.capture.mime_types"
private const val EXTRA_CAPTURED_SHARE_DISPLAY_NAMES = "conectate.capture.display_names"
private const val EXTRA_CAPTURED_SHARE_SIZES = "conectate.capture.sizes"
private const val EXTRA_CAPTURED_SHARE_FAILED_COUNT = "conectate.capture.failed_count"
private const val EXTRA_CAPTURED_SHARE_ERROR = "conectate.capture.error"
private const val MAX_SHARED_TEXT_LENGTH = 100_000

fun isIncomingShareIntent(intent: Intent?): Boolean {
    return intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE
}

fun hasCapturedIncomingShare(intent: Intent?): Boolean {
    return capturedIncomingShareId(intent) != null
}

internal fun capturedIncomingShareId(intent: Intent?): String? = try {
    intent?.getStringExtra(EXTRA_CAPTURED_SHARE_ID)?.trim()?.takeIf {
        UUID.fromString(it).toString().equals(it, ignoreCase = true)
    }?.lowercase()
} catch (_: RuntimeException) { null }

fun incomingShareText(intent: Intent): String? {
    val explicit = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotBlank() }
    val clipText = buildList {
        val clipData = intent.clipData
        if (clipData != null) {
            repeat(clipData.itemCount) { index ->
                clipData.getItemAt(index).text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.distinct().joinToString("\n").ifBlank { null }
    return (explicit ?: clipText)
        ?.trim()
        ?.take(MAX_SHARED_TEXT_LENGTH)
        ?.takeIf { it.isNotBlank() }
}

fun incomingShareUris(intent: Intent): List<Uri> {
    if (!isIncomingShareIntent(intent)) return emptyList()
    return buildList {
        intent.parcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let(::add)
        intent.parcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
        intent.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index ->
                clipData.getItemAt(index).uri?.let(::add)
            }
        }
        intent.data
            ?.takeIf { it.scheme.equals("content", true) || it.scheme.equals("file", true) }
            ?.let(::add)
    }.distinctBy(Uri::toString)
}

fun parseIncomingShareIntent(context: Context, intent: Intent, trustedCaptureId: String? = null): IncomingSharePayload? {
    if (!isIncomingShareIntent(intent)) return null
    val uris = incomingShareUris(intent)
    val text = incomingShareText(intent)
    val subject = listOf(
        intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
        intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString()
    ).firstOrNull { !it.isNullOrBlank() }?.trim()?.take(500)
    // Extras supplied by another app are not proof that a private capture belongs to this task.
    val capturedId = capturedIncomingShareId(intent)?.takeIf { it == trustedCaptureId }
    if (uris.isEmpty() && text.isNullOrBlank() && subject.isNullOrBlank() && capturedId == null) return null

    val capturedMimeTypes = if (capturedId != null) intent.getStringArrayListExtra(EXTRA_CAPTURED_SHARE_MIME_TYPES).orEmpty() else emptyList()
    val capturedDisplayNames = if (capturedId != null) intent.getStringArrayListExtra(EXTRA_CAPTURED_SHARE_DISPLAY_NAMES).orEmpty() else emptyList()
    val capturedSizes = if (capturedId != null) intent.getLongArrayExtra(EXTRA_CAPTURED_SHARE_SIZES) ?: LongArray(0) else LongArray(0)
    val fallbackMime = canonicalizeMediaContentType(intent.type)
    var rejectedSources = 0
    val items = uris.take(MAX_INCOMING_SHARE_ITEMS).mapIndexedNotNull { index, uri ->
        if (!mayReadIncomingShareSource(context, uri, capturedId)) {
            rejectedSources++
            return@mapIndexedNotNull null
        }
        val providerMime = canonicalizeMediaContentType(capturedMimeTypes.getOrNull(index))
            ?: canonicalizeMediaContentType(runCatching { context.contentResolver.getType(uri) }.getOrNull())
            ?: fallbackMime
        val displayName = capturedDisplayNames.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: querySharedDisplayName(context, uri)
        val nameMime = guessMimeTypeFromFileName(displayName)
        val mimeType = when {
            nameMime != null && (providerMime == null || providerMime.isGenericShareMime() || providerMime == "application/zip") -> nameMime
            providerMime != null -> providerMime
            nameMime != null -> nameMime
            else -> "application/octet-stream"
        }
        IncomingSharedItem(
            uri = uri,
            mimeType = mimeType,
            displayName = displayName,
            sizeBytes = capturedSizes.getOrNull(index)?.coerceAtLeast(0L) ?: querySharedSize(context, uri),
            kind = classifyIncomingShareKind(mimeType, displayName)
        )
    }

    return IncomingSharePayload(
        id = capturedId ?: UUID.randomUUID().toString(),
        text = text,
        subject = subject,
        items = items,
        receivedAtMs = if (capturedId != null) intent.getLongExtra(
            EXTRA_CAPTURED_SHARE_RECEIVED_AT,
            System.currentTimeMillis()
        ) else System.currentTimeMillis(),
        failedItemCount = ((if (capturedId != null) intent.getIntExtra(EXTRA_CAPTURED_SHARE_FAILED_COUNT, 0).coerceAtLeast(0).toLong() else 0L) +
            rejectedSources + (uris.size - MAX_INCOMING_SHARE_ITEMS).coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        captureError = if (capturedId != null) intent.getStringExtra(EXTRA_CAPTURED_SHARE_ERROR)?.takeIf { it.isNotBlank() } else null
    )
}

fun IncomingSharePayload.toCapturedShareIntent(): Intent {
    val streams = ArrayList(items.map(IncomingSharedItem::uri))
    val action = if (streams.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
    return Intent(action).apply {
        type = aggregateShareMimeType(items)
        putExtra(EXTRA_CAPTURED_SHARE_ID, id)
        putExtra(EXTRA_CAPTURED_SHARE_RECEIVED_AT, receivedAtMs)
        putStringArrayListExtra(
            EXTRA_CAPTURED_SHARE_MIME_TYPES,
            ArrayList(items.map(IncomingSharedItem::mimeType))
        )
        putStringArrayListExtra(
            EXTRA_CAPTURED_SHARE_DISPLAY_NAMES,
            ArrayList(items.map { it.displayName.orEmpty() })
        )
        putExtra(EXTRA_CAPTURED_SHARE_SIZES, items.map(IncomingSharedItem::sizeBytes).toLongArray())
        putExtra(EXTRA_CAPTURED_SHARE_FAILED_COUNT, failedItemCount)
        captureError?.let { putExtra(EXTRA_CAPTURED_SHARE_ERROR, it) }
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        when (streams.size) {
            0 -> Unit
            1 -> putExtra(Intent.EXTRA_STREAM, streams.first())
            else -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
        }
    }
}

fun takeIncomingSharePersistablePermissions(context: Context, intent: Intent) {
    val canPersist = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
    val canRead = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    if (!canPersist || !canRead) return
    incomingShareUris(intent)
        .take(MAX_INCOMING_SHARE_ITEMS)
        .filter { it.scheme.equals("content", ignoreCase = true) }
        .filter { mayReadIncomingShareSource(context, it) }
        .forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
}

fun isSharedTextLink(text: String?): Boolean {
    val value = text?.trim().orEmpty()
    if (value.isBlank() || value.any(Char::isWhitespace)) return false
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
    return uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
}

private fun aggregateShareMimeType(items: List<IncomingSharedItem>): String {
    if (items.isEmpty()) return "text/plain"
    val mimeTypes = items.map { it.mimeType.substringBefore(';').lowercase() }.distinct()
    if (mimeTypes.size == 1) return mimeTypes.first()
    return when {
        mimeTypes.all { it.startsWith("image/") } -> "image/*"
        mimeTypes.all { it.startsWith("video/") } -> "video/*"
        mimeTypes.all { it.startsWith("audio/") } -> "audio/*"
        else -> "*/*"
    }
}

private fun String.isGenericShareMime(): Boolean =
    this == "application/octet-stream" || this == "binary/octet-stream" ||
        this == "*/*" || endsWith("/*")

private fun querySharedDisplayName(context: Context, uri: Uri): String? {
    val fromProvider = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

private fun querySharedSize(context: Context, uri: Uri): Long {
    return runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.coerceAtLeast(0L)
        }
    }.getOrNull() ?: 0L
}

private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(key: String): T? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }.getOrNull()
}

private inline fun <reified T : Parcelable> Intent.parcelableArrayListExtraCompat(key: String): ArrayList<T>? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(key)
        }
    }.getOrNull()
}

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

fun isDeviceLocationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        manager.isLocationEnabled
    } else {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }
}

/**
 * Obtiene una lectura actual cuando la caché está vacía o vencida. Varias capas
 * de Android conceden el permiso antes de que exista un lastKnownLocation, por
 * lo que consultar sólo esa caché produce un falso error en el primer uso.
 */
@SuppressLint("MissingPermission")
suspend fun getBestCurrentLocation(
    context: Context,
    freshForMillis: Long = 2 * 60_000L,
    timeoutMillis: Long = 12_000L
): Location? {
    if (!hasLocationPermission(context) || !isDeviceLocationEnabled(context)) return null

    val cached = getBestLastKnownLocation(context)
    val cachedAge = cached?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
    if (cached != null && cachedAge in 0..freshForMillis) return cached

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return cached
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { candidate ->
            runCatching { manager.isProviderEnabled(candidate) }.getOrDefault(false)
        } ?: return cached

    val current = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine<Location?> { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                runCatching {
                    manager.getCurrentLocation(
                        provider,
                        cancellationSignal,
                        ContextCompat.getMainExecutor(context)
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }
                    @Deprecated("Legacy LocationListener callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure {
                    manager.removeUpdates(listener)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }
    return current ?: cached
}

fun formatCoordinate(value: Double): String {
    return String.format(Locale.US, "%.6f", value)
}

fun resolvePickedContact(context: Context, uri: Uri): DeviceContactPayload? = runCatching {
    resolvePickedContactInternal(context, uri)
}.onFailure { error ->
    android.util.Log.w(
        "ContactShare",
        "No se pudo leer el contacto desde ${uri.authority.orEmpty()}",
        error
    )
}.getOrNull()

private fun resolvePickedContactInternal(context: Context, uri: Uri): DeviceContactPayload? {
    val resolver = context.contentResolver

    // El selector concede acceso temporal únicamente a la fila elegida. Leer primero
    // esa fila evita consultar toda la agenda (READ_CONTACTS) y funciona también con
    // proveedores de contactos de Motorola, Samsung, Xiaomi y Google.
    val directPhonePayload = runCatching<DeviceContactPayload?> {
        resolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.safeString(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.Contacts.DISPLAY_NAME
            )
            val phone = normalizePhone(
                cursor.safeString(ContactsContract.CommonDataKinds.Phone.NUMBER)
            )
            if (name.isNotBlank() && phone.isNotBlank()) {
                DeviceContactPayload(
                    name = name,
                    phone = phone,
                    phones = listOf(phone)
                )
            } else {
                null
            }
        }
    }.getOrNull()
    if (directPhonePayload != null) return directPhonePayload

    // Compatibilidad con proveedores que devuelven una fila Contactables.
    val selectedContactable = runCatching<DeviceContactPayload?> {
        resolver.query(
            uri,
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.MIMETYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.safeString(ContactsContract.Contacts.DISPLAY_NAME)
            val selectedValue = cursor.safeString(ContactsContract.Data.DATA1)
            val mimeType = cursor.safeString(ContactsContract.Data.MIMETYPE)
            val phone = if (mimeType == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                normalizePhone(selectedValue)
            } else {
                ""
            }
            val email = if (mimeType == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) {
                normalizeEmail(selectedValue)
            } else {
                ""
            }
            if (name.isNotBlank() && (phone.isNotBlank() || email.isNotBlank())) {
                DeviceContactPayload(
                    name = name,
                    phone = phone,
                    email = email.ifBlank { null },
                    phones = listOfNotNull(phone.takeIf(String::isNotBlank)),
                    emails = listOfNotNull(email.takeIf(String::isNotBlank))
                )
            } else {
                null
            }
        }
    }.getOrNull()
    if (selectedContactable != null) return selectedContactable

    // Último recurso para proveedores que devuelven la URI general del contacto.
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
    if (contactId.isBlank() || contactName.isBlank()) return null

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

private fun android.database.Cursor.safeString(vararg columnNames: String): String {
    return columnNames.firstNotNullOfOrNull { columnName ->
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) null else runCatching { getString(index) }.getOrNull()
    }.orEmpty().trim()
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
