package conectate.com.core.profile

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Canonical profile-link contract shared by the editor and the repository.
 * It deliberately mirrors the validation performed by the Ubuntu API so a
 * harmless URL normalization (for example, adding the root slash) is never
 * reported as a failed save.
 */
object ProfileSocialLinks {
    private val allowedHosts = mapOf(
        "instagram" to setOf("instagram.com", "www.instagram.com"),
        "tiktok" to setOf("tiktok.com", "www.tiktok.com"),
        "facebook" to setOf("facebook.com", "www.facebook.com", "m.facebook.com"),
        "x" to setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com"),
    )

    private val supportedPlatforms = listOf("instagram", "tiktok", "facebook", "x", "whatsapp")

    fun normalize(values: Map<String, String>): Map<String, String> = buildMap {
        supportedPlatforms.forEach { platform ->
            normalizeValue(platform, values[platform].orEmpty())?.let { put(platform, it) }
        }
    }

    fun isValidInput(platform: String, value: String): Boolean =
        value.isBlank() || normalizeValue(platform, value) != null

    fun equivalent(expected: Map<String, String>, actual: Map<String, String>): Boolean =
        normalize(expected) == normalize(actual)

    fun displayHandle(platform: String, rawValue: String): String {
        val normalized = normalizeValue(platform, rawValue) ?: return ""
        if (platform == "whatsapp") return "+$normalized"
        return runCatching {
            val uri = URI(normalized)
            val rawSegment = uri.rawPath
                .orEmpty()
                .split('/')
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val decoded = URLDecoder.decode(rawSegment, StandardCharsets.UTF_8.name())
                .removePrefix("@")
                .trim()
            val value = if (decoded.equals("profile.php", ignoreCase = true)) {
                uri.rawQuery
                    .orEmpty()
                    .split('&')
                    .firstOrNull { it.startsWith("id=") }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                    .orEmpty()
            } else {
                decoded
            }
            value.takeIf(String::isNotBlank)?.let { "@$it" }.orEmpty()
        }.getOrDefault("")
    }

    private fun normalizeValue(platform: String, rawValue: String): String? {
        val value = rawValue.trim().take(240)
        if (value.isBlank()) return null
        if (platform == "whatsapp") {
            val digits = value.filter(Char::isDigit).take(18)
            return digits.takeIf { it.length >= 7 }
        }

        val hosts = allowedHosts[platform] ?: return null
        return runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
            if (!uri.scheme.equals("https", ignoreCase = true) || host !in hosts) {
                return@runCatching null
            }
            if (!uri.userInfo.isNullOrBlank() || (uri.port != -1 && uri.port != 443)) {
                return@runCatching null
            }

            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            buildString {
                append("https://")
                append(host)
                append(path)
                uri.rawQuery?.takeIf(String::isNotBlank)?.let { append('?').append(it) }
                uri.rawFragment?.takeIf(String::isNotBlank)?.let { append('#').append(it) }
            }
        }.getOrNull()
    }
}
