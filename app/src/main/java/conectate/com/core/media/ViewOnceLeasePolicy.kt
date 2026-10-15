package conectate.com.core.media

import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class ViewOnceLease(val url: String, val bytes: Long, val kind: String, val expiresAtMillis: Long)

internal object ViewOnceLeasePolicy {
    const val MAX_BYTES = 100L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, backend: String, now: Long = System.currentTimeMillis()): ViewOnceLease {
        val value = json.parseToJsonElement(raw)
        val row = when (value) {
            is JsonObject -> value
            is JsonArray -> value.singleOrNull() as? JsonObject
            else -> null
        } ?: throw IllegalArgumentException("No se pudo autorizar este archivo.")
        val allowed = row["allowed"]?.jsonPrimitive
        val consumed = row["already_consumed"]?.jsonPrimitive
        check(allowed?.isString == false && allowed?.booleanOrNull == true && consumed?.isString == false && consumed?.booleanOrNull == false) { "Este contenido ya fue visto o no está disponible." }
        val url = row["media_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(StorageReadPolicy.accepts(url, backend)) { "El archivo no pertenece al servidor autorizado." }
        val uri = URI(url)
        require(uri.rawFragment == null && Regex("lease=[A-Za-z0-9_-]{43}").matches(uri.rawQuery.orEmpty())) { "La autorización del archivo no es válida." }
        val expires = Instant.parse(row["lease_expires_at"]?.jsonPrimitive?.contentOrNull.orEmpty()).toEpochMilli()
        require(expires > now && expires <= now + 6 * 60_000) { "La autorización del archivo caducó." }
        val size = row["media_size_bytes"]?.jsonPrimitive
        val bytes = size?.takeUnless { it.isString }?.longOrNull ?: 0
        require(bytes in 1..MAX_BYTES) { "El archivo supera el tamaño permitido." }
        val kind = row["content_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(kind in setOf("view_once_image", "view_once_video")) { "El tipo de archivo no es válido." }
        return ViewOnceLease(url, bytes, kind, expires)
    }
}
