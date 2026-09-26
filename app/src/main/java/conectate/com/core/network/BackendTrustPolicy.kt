package conectate.com.core.network

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object BackendTrustPolicy {
    private const val VERSION = "novvin1"
    private const val MAX_DISCOVERY_TTL_SECONDS = 7L * 24L * 60L * 60L
    private const val CLOCK_SKEW_SECONDS = 5L * 60L

    data class VerifiedDiscovery(
        val url: String,
        val expiresAtEpochSeconds: Long,
        val envelope: String
    )

    fun canonicalBaseUrl(value: String?, allowHttp: Boolean = false): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            val parsed = URI(raw)
            val scheme = parsed.scheme?.lowercase() ?: return@runCatching null
            if (scheme != "https" && !(allowHttp && scheme == "http")) return@runCatching null
            if (parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null) {
                return@runCatching null
            }
            if (parsed.rawPath?.let { it.isNotEmpty() && it != "/" } == true) return@runCatching null
            val host = parsed.host?.lowercase()?.takeIf {
                it.isNotBlank() && !it.endsWith('.') && it.matches(Regex("^[a-z0-9.-]+$"))
            } ?: return@runCatching null
            val port = parsed.port
            val tailscaleFunnelPort = port == 8443 && host.endsWith(".ts.net")
            if (scheme == "https" && port !in intArrayOf(-1, 443) && !tailscaleFunnelPort) {
                return@runCatching null
            }
            if (scheme == "http" && !allowHttp) return@runCatching null
            val portSuffix = when {
                port == -1 || (scheme == "https" && port == 443) -> ""
                else -> ":$port"
            }
            "$scheme://$host$portSuffix"
        }.getOrNull()
    }

    fun isExplicitlyTrusted(
        candidate: String?,
        configuredBackend: String?,
        allowHttp: Boolean = false
    ): Boolean {
        val canonicalCandidate = canonicalBaseUrl(candidate, allowHttp)
        val canonicalConfigured = canonicalBaseUrl(configuredBackend, allowHttp)
        return canonicalCandidate != null && canonicalCandidate == canonicalConfigured
    }

    fun verifyDiscoveryEnvelope(
        envelopeValue: String?,
        publicKeySpkiBase64: String?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L
    ): VerifiedDiscovery? {
        val envelope = envelopeValue?.trim()?.removeSurrounding("\"").orEmpty()
        val publicKeyBase64 = publicKeySpkiBase64?.trim().orEmpty()
        if (envelope.isBlank() || publicKeyBase64.isBlank()) return null
        val parts = envelope.split(':', limit = 4)
        if (parts.size != 4 || parts[0] != VERSION) return null
        val expiresAt = parts[1].toLongOrNull() ?: return null
        if (expiresAt <= nowEpochSeconds - CLOCK_SKEW_SECONDS) return null
        if (expiresAt > nowEpochSeconds + MAX_DISCOVERY_TTL_SECONDS + CLOCK_SKEW_SECONDS) return null

        return runCatching {
            val decodedUrl = String(
                Base64.getUrlDecoder().decode(parts[2]),
                StandardCharsets.UTF_8
            )
            val url = canonicalBaseUrl(decodedUrl, allowHttp = false) ?: return@runCatching null
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))
            )
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(signatureInput(expiresAt, url))
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[3]))) return@runCatching null
            VerifiedDiscovery(
                url = url,
                expiresAtEpochSeconds = expiresAt,
                envelope = envelope
            )
        }.getOrNull()
    }

    private fun signatureInput(expiresAt: Long, url: String): ByteArray =
        "$VERSION\n$expiresAt\n$url".toByteArray(StandardCharsets.UTF_8)
}
