package conectate.com.presentation.profile

import android.net.Uri

private const val PublicBaseUrl = "https://conectate.com"

enum class SocialQrTarget {
    PROFILE,
    CHAT
}

data class SocialQrPayload(
    val target: SocialQrTarget,
    val userId: String
)

object SocialQrLinks {
    fun profileUrl(userId: String): String = "$PublicBaseUrl/u/${Uri.encode(userId.trim())}"

    fun chatUrl(userId: String): String = "$PublicBaseUrl/chat/${Uri.encode(userId.trim())}"

    fun profileDeepLink(userId: String): String = "conectate://profile/${Uri.encode(userId.trim())}"

    fun chatDeepLink(userId: String): String = "conectate://chat/${Uri.encode(userId.trim())}"
}

object SocialQrPayloadParser {
    fun parse(raw: String): SocialQrPayload? {
        val value = raw.trim()
        if (value.isBlank()) return null

        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val segments = uri.pathSegments

        if (scheme == "conectate") {
            val id = segments.firstOrNull()?.trim().orEmpty()
            return when {
                host == "profile" && id.isNotBlank() -> SocialQrPayload(SocialQrTarget.PROFILE, id)
                host == "chat" && id.isNotBlank() -> SocialQrPayload(SocialQrTarget.CHAT, id)
                else -> null
            }
        }

        if (scheme == "https" || scheme == "http") {
            val first = segments.firstOrNull()?.lowercase().orEmpty()
            val id = segments.getOrNull(1)?.trim().orEmpty()
            return when {
                first in setOf("u", "profile") && id.isNotBlank() ->
                    SocialQrPayload(SocialQrTarget.PROFILE, id)
                first == "chat" && id.isNotBlank() ->
                    SocialQrPayload(SocialQrTarget.CHAT, id)
                else -> null
            }
        }

        return null
    }
}
