package conectate.com.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChatContactPayload(
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val username: String? = null
) {
    val normalizedName: String
        get() = name.trim()

    val normalizedPhone: String?
        get() = phone?.trim()?.ifBlank { null } ?: phones.firstOrNull { it.isNotBlank() }

    val normalizedEmail: String?
        get() = email?.trim()?.lowercase()?.ifBlank { null } ?: emails.firstOrNull { it.isNotBlank() }?.trim()?.lowercase()

    fun hasReachableIdentity(): Boolean {
        return !normalizedPhone.isNullOrBlank() || !normalizedEmail.isNullOrBlank() || !username.isNullOrBlank()
    }
}

private val contactJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun encodeContactPayload(payload: ChatContactPayload): String {
    val normalizedPhones = payload.phones
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val normalizedEmails = payload.emails
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
    val normalized = payload.copy(
        name = payload.name.trim(),
        phone = payload.phone?.trim()?.ifBlank { null } ?: normalizedPhones.firstOrNull(),
        email = payload.email?.trim()?.lowercase()?.ifBlank { null } ?: normalizedEmails.firstOrNull(),
        phones = normalizedPhones,
        emails = normalizedEmails,
        username = payload.username?.trim()?.ifBlank { null }
    )
    return contactJson.encodeToString(normalized)
}

fun decodeContactPayload(raw: String): ChatContactPayload? {
    val content = raw.trim()
    if (content.isBlank()) return null

    val fromJson = runCatching { contactJson.decodeFromString<ChatContactPayload>(content) }
        .getOrNull()
        ?.let { parsed ->
            parsed.copy(
                name = parsed.name.trim(),
                phone = parsed.phone?.trim()?.ifBlank { null },
                email = parsed.email?.trim()?.lowercase()?.ifBlank { null },
                phones = parsed.phones.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                emails = parsed.emails.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct(),
                username = parsed.username?.trim()?.ifBlank { null }
            )
        }
    if (fromJson != null && fromJson.normalizedName.isNotBlank()) return fromJson

    // Legacy fallback: "👤 Name\n📞 +123\n✉️ mail@example.com"
    val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
    if (lines.isEmpty()) return null
    val name = lines.firstOrNull { it.startsWith("👤") }
        ?.removePrefix("👤")
        ?.trim()
        ?: lines.first().trim()
    val phone = lines.firstOrNull { it.startsWith("📞") }
        ?.removePrefix("📞")
        ?.trim()
        ?.ifBlank { null }
    val email = lines.firstOrNull { it.startsWith("✉️") || it.startsWith("✉") }
        ?.replace("✉️", "")
        ?.replace("✉", "")
        ?.trim()
        ?.lowercase()
        ?.ifBlank { null }

    if (name.isBlank()) return null
    return ChatContactPayload(
        name = name,
        phone = phone,
        email = email,
        phones = listOfNotNull(phone),
        emails = listOfNotNull(email)
    )
}
