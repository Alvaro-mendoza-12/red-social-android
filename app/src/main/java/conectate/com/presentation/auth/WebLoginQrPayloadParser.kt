package conectate.com.presentation.auth

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class WebLoginQrPayload(
    val pairId: String,
    val nonce: String
)

object WebLoginQrPayloadParser {

    fun parse(raw: String): WebLoginQrPayload? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        return buildPayload(
            pairId = extractParameter(trimmed, "pairId"),
            nonce = extractParameter(trimmed, "nonce")
        )
    }

    private fun buildPayload(
        pairId: String?,
        nonce: String?
    ): WebLoginQrPayload? {
        val cleanPairId = pairId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanNonce = nonce?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return WebLoginQrPayload(
            pairId = cleanPairId,
            nonce = cleanNonce
        )
    }

    private fun extractParameter(
        source: String,
        name: String
    ): String? {
        val regex = Regex("""(?:\?|&|^)$name=([^&#]+)""", RegexOption.IGNORE_CASE)
        val rawValue = regex.find(source)?.groupValues?.getOrNull(1) ?: return null
        return runCatching {
            URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
        }.getOrElse {
            rawValue
        }
    }
}
