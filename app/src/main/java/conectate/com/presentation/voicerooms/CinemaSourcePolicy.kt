package conectate.com.presentation.voicerooms

import java.net.URI

/** Defense-in-depth validation for user-provided cinema playback links. */
object CinemaSourcePolicy {
    data class Validation(
        val normalizedUrl: String? = null,
        val error: String? = null
    ) {
        val isValid: Boolean get() = normalizedUrl != null && error == null
    }

    fun validateManualUrl(rawUrl: String): Validation {
        val value = rawUrl.trim()
        if (value.isBlank()) return Validation(error = "Pega un enlace de video.")
        if (value.length > MAX_URL_CHARS) return Validation(error = "El enlace es demasiado largo.")

        val uri = runCatching { URI(value) }.getOrNull()
            ?: return Validation(error = "El enlace no es válido.")
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) {
            return Validation(error = "Solo se permiten enlaces HTTPS sin credenciales.")
        }
        val host = uri.host?.trim()?.lowercase()?.trimEnd('.')
            ?: return Validation(error = "El enlace no contiene un servidor válido.")
        if (isBlockedHost(host)) {
            return Validation(error = "No se permiten direcciones locales o privadas.")
        }

        val path = uri.path.orEmpty().lowercase()
        val supportedProvider = isTrustedHost(host, "drive.google.com") ||
            isTrustedHost(host, "archive.org")
        val directMedia = SUPPORTED_PATH_SUFFIXES.any(path::endsWith)
        if (!supportedProvider && !directMedia) {
            return Validation(
                error = "Usa un archivo directo MP4, WebM, HLS o DASH, o un enlace de Drive/Archive.org."
            )
        }
        return Validation(normalizedUrl = uri.normalize().toASCIIString())
    }

    fun isResolvedPlaybackUrl(rawUrl: String): Boolean = validateManualUrl(rawUrl).isValid

    private fun isTrustedHost(host: String, expected: String): Boolean =
        host == expected || host.endsWith(".$expected")

    private fun isBlockedHost(host: String): Boolean {
        if (host == "localhost" || BLOCKED_HOST_SUFFIXES.any(host::endsWith)) return true
        if (IPV4_REGEX.matches(host)) return true
        return host.startsWith("[") || host.endsWith("]") || ':' in host
    }

    private const val MAX_URL_CHARS = 4_096
    private val SUPPORTED_PATH_SUFFIXES = setOf(".mp4", ".m4v", ".webm", ".m3u8", ".mpd")
    private val BLOCKED_HOST_SUFFIXES = setOf(".localhost", ".local", ".internal", ".lan", ".home")
    private val IPV4_REGEX = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
}
