package conectate.com.auth.twoFactor

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP compatible with Google Authenticator (HMAC-SHA1, 30s, 6 digits).
 * Pure Android APIs with no desktop JVM SecureRandom providers.
 */
internal object AndroidTotp {
    private const val TIME_STEP_SECONDS = 30L
    private const val CODE_MODULUS = 1_000_000
    private const val SECRET_BYTES = 10
    private const val DEFAULT_WINDOW = 1

    fun generateSecret(): String {
        val buffer = ByteArray(SECRET_BYTES)
        SecureRandom().nextBytes(buffer)
        return TotpBase32.encode(buffer)
    }

    fun authorize(secret: String, code: Int, window: Int = DEFAULT_WINDOW): Boolean {
        if (code <= 0 || code >= CODE_MODULUS) return false
        val key = decodeSecret(secret) ?: return false
        val timeStep = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS
        for (offset in -window..window) {
            if (generateCode(key, timeStep + offset) == code) return true
        }
        return false
    }

    fun currentCode(secret: String): Int {
        val key = decodeSecret(secret) ?: return 0
        val timeStep = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS
        return generateCode(key, timeStep)
    }

    private fun decodeSecret(secret: String): ByteArray? {
        val normalized = secret.trim().uppercase().replace(" ", "").replace("-", "")
        if (normalized.isEmpty()) return null
        return runCatching { TotpBase32.decode(normalized) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun generateCode(key: ByteArray, counter: Long): Int {
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash.last().toInt() and 0x0F
        var truncated = 0
        for (i in 0 until 4) {
            truncated = (truncated shl 8) or (hash[offset + i].toInt() and 0xFF)
        }
        truncated = truncated and 0x7FFFFFFF
        return truncated % CODE_MODULUS
    }
}
