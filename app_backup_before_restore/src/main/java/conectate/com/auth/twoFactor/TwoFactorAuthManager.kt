package conectate.com.auth.twoFactor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TwoFactorAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val SHARED_PREFS_NAME = "two_factor_auth_prefs"
        private const val ACTIVE_SECRET_KEY = "active_secret"
        private const val PENDING_SECRET_KEY = "pending_secret"
        private const val BACKUP_CODES_KEY = "backup_codes"
        private const val IS_2FA_ENABLED_KEY = "is_2fa_enabled"
        private const val FAILED_ATTEMPTS_KEY = "failed_attempts"
        private const val LAST_ATTEMPT_TIME_KEY = "last_attempt_time"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 15 * 60 * 1000L
        private const val TOTP_PERIOD_SECONDS = 30
        private const val TOTP_DIGITS = 6
        private const val BACKUP_CODES_COUNT = 10
        private const val HOTP_ALGORITHM = "HmacSHA1"
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }

    private val securePrefs: SharedPreferences by lazy {
        createSecurePrefs(SHARED_PREFS_NAME)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _is2FAEnabled = MutableStateFlow(is2FAEnabledInternal())
    val is2FAEnabled: StateFlow<Boolean> = _is2FAEnabled.asStateFlow()

    private val _failedAttempts = MutableStateFlow(getFailedAttempts())
    val failedAttempts: StateFlow<Int> = _failedAttempts.asStateFlow()

    private val _isLockedOut = MutableStateFlow(isLockedOutInternal())
    val isLockedOut: StateFlow<Boolean> = _isLockedOut.asStateFlow()

    data class TOTPSecret(
        val secret: String,
        val issuer: String = "CONECTATE",
        val accountName: String,
        val algorithm: String = "SHA1",
        val digits: Int = TOTP_DIGITS,
        val period: Int = TOTP_PERIOD_SECONDS
    ) {
        fun toTOTPUri(): String {
            val issuerValue = Uri.encode(issuer)
            val accountValue = Uri.encode(accountName)
            return "otpauth://totp/$issuerValue:$accountValue?secret=$secret&issuer=$issuerValue&algorithm=$algorithm&digits=$digits&period=$period"
        }
    }

    @Serializable
    data class BackupCode(
        val code: String,
        val isUsed: Boolean = false,
        val usedAt: Long? = null
    )

    data class TwoFactorAuthResult(
        val success: Boolean,
        val error: String? = null,
        val requires2FA: Boolean = false,
        val totpSecret: TOTPSecret? = null,
        val qrCodeBitmap: Bitmap? = null,
        val backupCodes: List<String>? = null
    )

    suspend fun generateTOTPSecret(email: String): TwoFactorAuthResult {
        return try {
            val normalizedEmail = email.trim()
            if (normalizedEmail.isBlank()) {
                return TwoFactorAuthResult(success = false, error = "Email inválido")
            }

            val secret = generateSecureSecret()
            val backupCodes = generateBackupCodes()
            val totpSecret = TOTPSecret(secret = secret, accountName = normalizedEmail)

            securePrefs.edit()
                .putString(PENDING_SECRET_KEY, secret)
                .apply()
            storeBackupCodes(backupCodes.map { BackupCode(code = it) })

            TwoFactorAuthResult(
                success = true,
                requires2FA = true,
                totpSecret = totpSecret,
                qrCodeBitmap = null,
                backupCodes = backupCodes
            )
        } catch (e: Exception) {
            TwoFactorAuthResult(
                success = false,
                error = "No se pudo generar la configuración 2FA: ${e.message}"
            )
        }
    }

    suspend fun verifyTOTPCode(code: String, secret: String): TwoFactorAuthResult {
        if (isLockedOutInternal()) {
            return TwoFactorAuthResult(
                success = false,
                error = "Cuenta bloqueada temporalmente por demasiados intentos."
            )
        }

        return try {
            val normalizedCode = code.filter(Char::isDigit)
            val normalizedSecret = secret.trim().uppercase()
            val isValid = normalizedCode.length == TOTP_DIGITS &&
                generateValidTotpCodes(normalizedSecret).contains(normalizedCode)

            if (isValid) {
                resetFailedAttempts()
                TwoFactorAuthResult(success = true)
            } else {
                incrementFailedAttempts()
                TwoFactorAuthResult(success = false, error = "Codigo 2FA inválido")
            }
        } catch (e: Exception) {
            incrementFailedAttempts()
            TwoFactorAuthResult(
                success = false,
                error = "No se pudo verificar el codigo 2FA: ${e.message}"
            )
        }
    }

    suspend fun verifyBackupCode(code: String): TwoFactorAuthResult {
        if (isLockedOutInternal()) {
            return TwoFactorAuthResult(
                success = false,
                error = "Cuenta bloqueada temporalmente por demasiados intentos."
            )
        }

        return try {
            val normalizedCode = code.filter(Char::isDigit)
            val codes = getBackupCodes().toMutableList()
            val index = codes.indexOfFirst { it.code == normalizedCode && !it.isUsed }

            if (index == -1) {
                incrementFailedAttempts()
                TwoFactorAuthResult(success = false, error = "Codigo de respaldo inválido")
            } else {
                codes[index] = codes[index].copy(isUsed = true, usedAt = System.currentTimeMillis())
                storeBackupCodes(codes)
                resetFailedAttempts()
                TwoFactorAuthResult(success = true)
            }
        } catch (e: Exception) {
            incrementFailedAttempts()
            TwoFactorAuthResult(
                success = false,
                error = "No se pudo verificar el codigo de respaldo: ${e.message}"
            )
        }
    }

    suspend fun enable2FA(secret: String): TwoFactorAuthResult {
        return try {
            val normalizedSecret = secret.trim().uppercase()
            if (normalizedSecret.isBlank()) {
                return TwoFactorAuthResult(success = false, error = "Secret 2FA inválido")
            }

            computeTotpCode(normalizedSecret, currentCounter())

            securePrefs.edit()
                .putString(ACTIVE_SECRET_KEY, normalizedSecret)
                .putString(PENDING_SECRET_KEY, "")
                .putBoolean(IS_2FA_ENABLED_KEY, true)
                .apply()

            _is2FAEnabled.value = true
            resetFailedAttempts()
            TwoFactorAuthResult(success = true)
        } catch (e: Exception) {
            TwoFactorAuthResult(
                success = false,
                error = "No se pudo activar 2FA: ${e.message}"
            )
        }
    }

    suspend fun disable2FA(): TwoFactorAuthResult {
        return try {
            securePrefs.edit()
                .remove(ACTIVE_SECRET_KEY)
                .remove(PENDING_SECRET_KEY)
                .remove(BACKUP_CODES_KEY)
                .putBoolean(IS_2FA_ENABLED_KEY, false)
                .apply()

            _is2FAEnabled.value = false
            resetFailedAttempts()
            TwoFactorAuthResult(success = true)
        } catch (e: Exception) {
            TwoFactorAuthResult(
                success = false,
                error = "No se pudo desactivar 2FA: ${e.message}"
            )
        }
    }

    fun is2FARequired(): Boolean = is2FAEnabled.value

    suspend fun getCurrentTOTPCode(secret: String): String? {
        return runCatching {
            computeTotpCode(secret.trim().uppercase(), currentCounter())
        }.getOrNull()
    }

    fun getRemainingLockoutTime(): Long {
        if (!isLockedOutInternal()) return 0L
        val lastAttemptTime = securePrefs.getLong(LAST_ATTEMPT_TIME_KEY, 0L)
        val remaining = LOCKOUT_DURATION_MS - (System.currentTimeMillis() - lastAttemptTime)
        return remaining.coerceAtLeast(0L)
    }

    fun getUnusedBackupCodesCount(): Int {
        return getBackupCodes().count { !it.isUsed }
    }

    private fun createSecurePrefs(name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun generateSecureSecret(): String {
        val bytes = ByteArray(20)
        SecureRandom().nextBytes(bytes)
        return encodeBase32(bytes)
    }

    private fun generateBackupCodes(): List<String> {
        val random = SecureRandom()
        return List(BACKUP_CODES_COUNT) {
            buildString(8) {
                repeat(8) {
                    append(random.nextInt(10))
                }
            }
        }
    }

    private fun storeBackupCodes(codes: List<BackupCode>) {
        securePrefs.edit()
            .putString(BACKUP_CODES_KEY, json.encodeToString(codes))
            .apply()
    }

    private fun getBackupCodes(): List<BackupCode> {
        val raw = securePrefs.getString(BACKUP_CODES_KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<BackupCode>>(raw) }.getOrDefault(emptyList())
    }

    private fun incrementFailedAttempts() {
        val newAttempts = getFailedAttempts() + 1
        securePrefs.edit()
            .putInt(FAILED_ATTEMPTS_KEY, newAttempts)
            .putLong(LAST_ATTEMPT_TIME_KEY, System.currentTimeMillis())
            .apply()
        _failedAttempts.value = newAttempts
        _isLockedOut.value = isLockedOutInternal()
    }

    private fun resetFailedAttempts() {
        securePrefs.edit()
            .putInt(FAILED_ATTEMPTS_KEY, 0)
            .putLong(LAST_ATTEMPT_TIME_KEY, 0L)
            .apply()
        _failedAttempts.value = 0
        _isLockedOut.value = false
    }

    private fun getFailedAttempts(): Int {
        return securePrefs.getInt(FAILED_ATTEMPTS_KEY, 0)
    }

    private fun is2FAEnabledInternal(): Boolean {
        return securePrefs.getBoolean(IS_2FA_ENABLED_KEY, false)
    }

    private fun isLockedOutInternal(): Boolean {
        val attempts = getFailedAttempts()
        if (attempts < MAX_FAILED_ATTEMPTS) return false
        val lastAttemptTime = securePrefs.getLong(LAST_ATTEMPT_TIME_KEY, 0L)
        val stillLocked = System.currentTimeMillis() - lastAttemptTime < LOCKOUT_DURATION_MS
        if (!stillLocked) {
            resetFailedAttempts()
        }
        return stillLocked
    }

    private fun generateValidTotpCodes(secret: String): Set<String> {
        val counter = currentCounter()
        return setOf(counter - 1, counter, counter + 1)
            .filter { it >= 0L }
            .map { computeTotpCode(secret, it) }
            .toSet()
    }

    private fun currentCounter(): Long {
        return (System.currentTimeMillis() / 1000L) / TOTP_PERIOD_SECONDS
    }

    private fun computeTotpCode(secret: String, counter: Long): String {
        val key = decodeBase32(secret)
        val data = ByteArray(8)
        for (index in 7 downTo 0) {
            data[index] = (counter ushr ((7 - index) * 8)).toByte()
        }

        val mac = Mac.getInstance(HOTP_ALGORITHM)
        mac.init(SecretKeySpec(key, HOTP_ALGORITHM))
        val hash = mac.doFinal(data)
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val otp = binary % 1_000_000
        return otp.toString().padStart(TOTP_DIGITS, '0')
    }

    private fun encodeBase32(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        val output = StringBuilder(((bytes.size + 4) / 5) * 8)
        var buffer = bytes[0].toInt() and 0xFF
        var next = 1
        var bitsLeft = 8

        while (bitsLeft > 0 || next < bytes.size) {
            if (bitsLeft < 5) {
                if (next < bytes.size) {
                    buffer = (buffer shl 8) or (bytes[next].toInt() and 0xFF)
                    next++
                    bitsLeft += 8
                } else {
                    val pad = 5 - bitsLeft
                    buffer = buffer shl pad
                    bitsLeft += pad
                }
            }

            val index = (buffer shr (bitsLeft - 5)) and 0x1F
            bitsLeft -= 5
            output.append(BASE32_ALPHABET[index])
        }

        return output.toString()
    }

    private fun decodeBase32(value: String): ByteArray {
        val normalized = value.uppercase().filterNot { it == '=' || it.isWhitespace() }
        require(normalized.isNotBlank()) { "Secret 2FA vacío" }

        val output = ArrayList<Byte>((normalized.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0

        for (char in normalized) {
            val index = BASE32_ALPHABET.indexOf(char)
            require(index >= 0) { "Secret 2FA inválido" }
            buffer = (buffer shl 5) or index
            bitsLeft += 5

            if (bitsLeft >= 8) {
                output.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }

        return output.toByteArray()
    }
}
