package conectate.com.data.repository.collection

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object CollectionRepositorySupport {
    fun extractUpdatedIdsFromPatchBody(rawBody: String): Set<String> {
        if (rawBody.isBlank()) return emptySet()
        return runCatching {
            val element = Json.parseToJsonElement(rawBody)
            if (element !is JsonArray) return@runCatching emptySet<String>()
            element.mapNotNull { row ->
                row.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            }.toSet()
        }.getOrDefault(emptySet())
    }

    fun extractIdFromRepresentation(body: String, json: Json): String? {
        if (body.isBlank()) return null
        val asList = runCatching { json.decodeFromString<List<JsonObject>>(body) }.getOrNull()
        val fromList = asList?.firstOrNull()?.get("id")?.jsonPrimitive?.contentOrNull
        if (!fromList.isNullOrBlank()) return fromList

        val asObject = runCatching { json.decodeFromString<JsonObject>(body) }.getOrNull()
        return asObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    fun toPostgresTextArray(values: List<String>): String {
        return values.joinToString(prefix = "{", postfix = "}") { raw ->
            "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    fun isDuplicateKeyError(error: Exception): Boolean {
        var text = error.message?.lowercase() ?: return false
        return text.contains("duplicate key") || text.contains("23505")
    }

    fun isMissingForwardingColumnError(errorText: String): Boolean {
        val normalized = errorText.lowercase()
        return normalized.contains("column") &&
            (
                normalized.contains("is_forwarded") ||
                    normalized.contains("forwarded_from_message_id") ||
                    normalized.contains("forwarded_from_user_id")
                )
    }

    fun isRetryableNetworkError(error: Exception, retryCodes: List<String>): Boolean {
        var text = error.message?.lowercase() ?: return false
        return retryCodes.any { code -> text.contains(code) }
    }
}



