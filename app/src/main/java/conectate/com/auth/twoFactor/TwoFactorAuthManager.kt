package conectate.com.auth.twoFactor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.warrenstrange.googleauth.GoogleAuthenticator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class TwoFactorAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val SHARED_PREFS_NAME = "two_factor_auth_prefs"
        private const val SECRET_KEY = "totp_secret"
        private const val BACKUP_CODES_KEY = "backup_codes"
        private const val IS_2FA_ENABLED_KEY = "is_2fa_enabled"
        private const val FAILED_ATTEMPTS_KEY = "failed_attempts"
        private const val LAST_ATTEMPT_TIME_KEY = "last_attempt_time"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 15 * 60 * 1000L
        private const val TOTP_PERIOD_SECONDS = 30
        private const val TOTP_DIGITS = 6
        private const val BACKUP_CODES_COUNT = 10
    }

    @Serializable
    data class BackupCode(
        val code: String,
        val isUsed: Boolean = false,
        val usedAt: Long? = null
    )

    data class TOTPSecret(
        val secret: String,
        val issuer: String = "Conectate",
        val accountName: String,
        val algorithm: String = "SHA1",
        val digits: Int = TOTP_DIGITS,
        val period: Int = TOTP_PERIOD_SECONDS
    ) {
        fun toTOTPUri(): String {
            val normalizedIssuer = issuer.trim().replace(" ", "%20")
            val normalizedAccount = accountName.trim().replace(" ", "%20")
            return "otpauth://totp/$normalizedIssuer:$normalizedAccount?secret=$secret&issuer=$normalizedIssuer&algorithm=$algorithm&digits=$digits&period=$period"
        }
    }

    data class TwoFactorAuthResult(
        val success: Boolean,
        val error: String? = null,
        val requires2FA: Boolean = false,
        val totpSecret: TOTPSecret? = null,
        val qrCodeBitmap: Bitmap? = null,
        val backupCodes: List<String>? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            SHARED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val authenticator = GoogleAuthenticator()

    private val _is2FAEnabled = MutableStateFlow(is2FAEnabledStored())
    val is2FAEnabled: StateFlow<Boolean> = _is2FAEnabled.asStateFlow()

    private val _failedAttempts = MutableStateFlow(readFailedAttempts())
    val failedAttempts: StateFlow<Int> = _failedAttempts.asStateFlow()

    private val _isLockedOut = MutableStateFlow(isLockedOutNow())
    val isLockedOut: StateFlow<Boolean> = _isLockedOut.asStateFlow()

    suspend fun generateTOTPSecret(email: String): TwoFactorAuthResult {
        return runCatching {
            val secret = authenticator.createCredentials().key
            val totpSecret = TOTPSecret(
                secret = secret,
                accountName = email.trim().lowercase()
            )
            val backupCodes = generateBackupCodes()

            storeTOTPSecret(secret)
            storeBackupCodes(backupCodes)
            securePrefs.edit()
                .putBoolean(IS_2FA_ENABLED_KEY, false)
                .apply()
            refreshState()

            TwoFactorAuthResult(
                success = true,
                requires2FA = true,
                totpSecret = totpSecret,
                qrCodeBitmap = generateQRCode(totpSecret.toTOTPUri()),
                backupCodes = backupCodes.map { it.code }
            )
        }.getOrElse { error ->
            TwoFactorAuthResult(
                success = false,
                error = "No se pudo preparar la verificación en dos pasos."
            )
        }
    }

    suspend fun verifyTOTPCode(code: String, secret: String): TwoFactorAuthResult {
        if (isLockedOutNow()) {
            return TwoFactorAuthResult(
                success = false,
                error = "Demasiados intentos fallidos. Intenta de nuevo en unos minutos."
            )
        }

        val numericCode = code.trim().toIntOrNull()
            ?: return registerFailedAttempt("El código de verificación no es válido.")

        val isValid = runCatching {
            authenticator.authorize(secret, numericCode)
        }.getOrDefault(false)

        return if (isValid) {
            resetFailedAttempts()
            TwoFactorAuthResult(success = true)
        } else {
            registerFailedAttempt("El código de verificación no coincide.")
        }
    }

    suspend fun verifyBackupCode(code: String): TwoFactorAuthResult {
        if (isLockedOutNow()) {
            return TwoFactorAuthResult(
                success = false,
                error = "Demasiados intentos fallidos. Intenta de nuevo en unos minutos."
            )
        }

        val normalizedCode = code.trim()
        val codes = getBackupCodes().toMutableList()
        val codeIndex = codes.indexOfFirst { it.code == normalizedCode && !it.isUsed }

        return if (codeIndex >= 0) {
            codes[codeIndex] = codes[codeIndex].copy(
                isUsed = true,
                usedAt = System.currentTimeMillis()
            )
            storeBackupCodes(codes)
            resetFailedAttempts()
            TwoFactorAuthResult(success = true)
        } else {
            registerFailedAttempt("Ese código de respaldo ya no está disponible.")
        }
    }

    suspend fun enable2FA(secret: String): TwoFactorAuthResult {
        val normalizedSecret = secret.trim()
        if (normalizedSecret.isBlank()) {
            return TwoFactorAuthResult(
                success = false,
                error = "No se pudo activar la verificación porque falta el secreto."
            )
        }

        return runCatching {
            storeTOTPSecret(normalizedSecret)
            if (getBackupCodes().isEmpty()) {
                storeBackupCodes(generateBackupCodes())
            }
            securePrefs.edit()
                .putBoolean(IS_2FA_ENABLED_KEY, true)
                .apply()
            refreshState()
            TwoFactorAuthResult(success = true)
        }.getOrElse {
            TwoFactorAuthResult(
                success = false,
                error = "No se pudo activar la verificación en dos pasos."
            )
        }
    }

    suspend fun disable2FA(): TwoFactorAuthResult {
        return runCatching {
            securePrefs.edit()
                .remove(SECRET_KEY)
                .remove(BACKUP_CODES_KEY)
                .putBoolean(IS_2FA_ENABLED_KEY, false)
                .apply()
            resetFailedAttempts()
            refreshState()
            TwoFactorAuthResult(success = true)
        }.getOrElse {
            TwoFactorAuthResult(
                success = false,
                error = "No se pudo desactivar la verificación en dos pasos."
            )
        }
    }

    fun is2FARequired(): Boolean = is2FAEnabledStored()

    suspend fun getCurrentTOTPCode(secret: String): String? {
        val numericCode = runCatching {
            authenticator.getTotpPassword(secret)
        }.getOrNull() ?: return null
        return numericCode.toString().padStart(TOTP_DIGITS, '0')
    }

    fun getStoredTOTPSecret(): String? {
        return securePrefs.getString(SECRET_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getStoredSecret(): String? = getStoredTOTPSecret()

    fun getRemainingLockoutTime(): Long {
        if (!isLockedOutNow()) return 0L
        val lastAttemptAt = securePrefs.getLong(LAST_ATTEMPT_TIME_KEY, 0L)
        return (LOCKOUT_DURATION_MS - (System.currentTimeMillis() - lastAttemptAt)).coerceAtLeast(0L)
    }

    fun getUnusedBackupCodesCount(): Int {
        return getBackupCodes().count { !it.isUsed }
    }

    private fun generateQRCode(text: String): Bitmap {
        return try {
            val bitMatrix: BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val bitmap = Bitmap.createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.RGB_565)
            for (x in 0 until bitMatrix.width) {
                for (y in 0 until bitMatrix.height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (error: WriterException) {
            throw IllegalStateException("Failed to generate QR code", error)
        }
    }

    private fun generateBackupCodes(): List<BackupCode> {
        return List(BACKUP_CODES_COUNT) {
            BackupCode(
                code = (10000000..99999999).random().toString()
            )
        }
    }

    private fun storeTOTPSecret(secret: String) {
        securePrefs.edit()
            .putString(SECRET_KEY, secret)
            .apply()
    }

    private fun storeBackupCodes(codes: List<BackupCode>) {
        securePrefs.edit()
            .putString(BACKUP_CODES_KEY, json.encodeToString(codes))
            .apply()
    }

    private fun getBackupCodes(): List<BackupCode> {
        val rawValue = securePrefs.getString(BACKUP_CODES_KEY, null).orEmpty()
        if (rawValue.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<BackupCode>>(rawValue)
        }.getOrElse {
            emptyList()
        }
    }

    private fun registerFailedAttempt(message: String): TwoFactorAuthResult {
        val attempts = (readFailedAttempts() + 1).coerceAtMost(MAX_FAILED_ATTEMPTS)
        securePrefs.edit()
            .putInt(FAILED_ATTEMPTS_KEY, attempts)
            .putLong(LAST_ATTEMPT_TIME_KEY, System.currentTimeMillis())
            .apply()
        refreshState()
        return TwoFactorAuthResult(success = false, error = message)
    }

    private fun resetFailedAttempts() {
        securePrefs.edit()
            .putInt(FAILED_ATTEMPTS_KEY, 0)
            .remove(LAST_ATTEMPT_TIME_KEY)
            .apply()
        refreshState()
    }

    private fun refreshState() {
        if (!isLockoutStillActive()) {
            clearExpiredLockout()
        }
        _is2FAEnabled.value = is2FAEnabledStored()
        _failedAttempts.value = readFailedAttempts()
        _isLockedOut.value = isLockedOutNow()
    }

    private fun is2FAEnabledStored(): Boolean {
        return securePrefs.getBoolean(IS_2FA_ENABLED_KEY, false) && !getStoredTOTPSecret().isNullOrBlank()
    }

    private fun readFailedAttempts(): Int {
        return securePrefs.getInt(FAILED_ATTEMPTS_KEY, 0).coerceAtLeast(0)
    }

    private fun isLockedOutNow(): Boolean {
        return readFailedAttempts() >= MAX_FAILED_ATTEMPTS && isLockoutStillActive()
    }

    private fun isLockoutStillActive(): Boolean {
        val lastAttemptAt = securePrefs.getLong(LAST_ATTEMPT_TIME_KEY, 0L)
        if (lastAttemptAt <= 0L) return false
        return System.currentTimeMillis() - lastAttemptAt < LOCKOUT_DURATION_MS
    }

    private fun clearExpiredLockout() {
        if (readFailedAttempts() == 0) return
        securePrefs.edit()
            .putInt(FAILED_ATTEMPTS_KEY, 0)
            .remove(LAST_ATTEMPT_TIME_KEY)
            .apply()
    }
}
