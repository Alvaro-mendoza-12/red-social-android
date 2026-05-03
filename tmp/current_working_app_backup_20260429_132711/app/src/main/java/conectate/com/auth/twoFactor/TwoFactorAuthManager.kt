package conectate.com.auth.twoFactor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import com.warrenstrange.googleauth.GoogleAuthenticator
import com.warrenstrange.googleauth.GoogleAuthenticatorKey
import com.warrenstrange.googleauth.GoogleAuthenticatorKey.Builder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.experimental.and
import kotlin.experimental.or

@Singleton
class TwoFactorAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val SHARED_PREFS_NAME = "two_factor_auth_prefs"
        private const val SECRET_KEY_ALIAS = "two_factor_secret_key"
        private const val BACKUP_CODES_KEY = "backup_codes"
        private const val IS_2FA_ENABLED_KEY = "is_2fa_enabled"
        private const val FAILED_ATTEMPTS_KEY = "failed_attempts"
        private const val LAST_ATTEMPT_TIME_KEY = "last_attempt_time"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 15 * 60 * 1000L // 15 minutes
        private const val TOTP_PERIOD_SECONDS = 30
        private const val TOTP_DIGITS = 6
        private const val BACKUP_CODES_COUNT = 10
    }
    
    private val securePrefs: EncryptedSharedPreferences by lazy {
        var masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setEncryptionScheme(MasterKey.EncryptionScheme.AES256_GCM_HKDF_SHA256)
            .build()
        
        EncryptedSharedPreferences.create(
            context,
            SHARED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    private val _is2FAEnabled = MutableStateFlow(is2FAEnabled())
    val is2FAEnabled: StateFlow<Boolean> = _is2FAEnabled.asStateFlow()
    
    private val _failedAttempts = MutableStateFlow(getFailedAttempts())
    val failedAttempts: StateFlow<Int> = _failedAttempts.asStateFlow()
    
    private val _isLockedOut = MutableStateFlow(isLockedOut())
    val isLockedOut: StateFlow<Boolean> = _isLockedOut.asStateFlow()
    
    data class TOTPSecret(
        val secret: String,
        val issuer: String = "CONÉCTATE",
        val accountName: String,
        val algorithm: String = "SHA1",
        val digits: Int = TOTP_DIGITS,
        val period: Int = TOTP_PERIOD_SECONDS
    ) {
        fun toTOTPUri(): String {
            val encodedSecret = secret.replace('+', '-').replace('/', '_').replace('=', '')
            return "otpauth://totp/$issuer:$accountName?secret=$encodedSecret&issuer=$issuer&algorithm=$algorithm&digits=$digits&period=$period"
        }
    }
    
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
    
    /**
     * Generate a new TOTP secret for 2FA setup
     */
    suspend fun generateTOTPSecret(email: String): TwoFactorAuthResult {
        return try {
            var secret = generateSecureSecret()
            var totpSecret = TOTPSecret(
                secret = secret,
                accountName = email
            )
            
            var qrCodeBitmap = generateQRCode(totpSecret.toTOTPUri())
            var backupCodes = generateBackupCodes()
            
            // Store backup codes securely
            storeBackupCodes(backupCodes)
            
            TwoFactorAuthResult(
                success = true,
                requires2FA = true,
                totpSecret = totpSecret,
                qrCodeBitmap = qrCodeBitmap,
                backupCodes = backupCodes
            )
        } catch (e: Exception) {
            TwoFactorAuthResult(
                success = false,
                error = "Failed to generate TOTP secret: ${e.message}"
            )
        }
    }
    
    /**
     * Verify TOTP code
     */
    suspend fun verifyTOTPCode(code: String, secret: String): TwoFactorAuthResult {
        if (isLockedOut()) {
            return TwoFactorAuthResult(
                success = false,
                error = "Account locked due to too many failed attempts. Try again later."
            )
        }
        
        return try {
            var googleAuthenticator = GoogleAuthenticator()
            var isValid = googleAuthenticator.authorize(secret, code.toInt())
            
            if (isValid) {
                resetFailedAttempts()
                TwoFactorAuthResult(success = true)
            } else {
                incrementFailedAttempts()
                TwoFactorAuthResult(
                    success = false,
                    error = "Invalid TOTP code"
                )
            }
        } catch (e: Exception) {
            TwoFactorAuthResult(
                success = false,
                error = "Failed to verify TOTP code: ${e.message}"
            )
        }
    }
    
    /**
     * Verify backup code
     */
    suspend fun verifyBackupCode(code: String): TwoFactorAuthResult {
        if (isLockedOut()) {
            return TwoFactorAuthResult(
                success = false,
                error = "Account locked due to too many failed attempts. Try again later."
            )
        }
        
        return try {
            var backupCodes = getBackupCodes()
            val codeIndex = backupCodes.indexOfFirst { it.code == code && !it.isUsed }
            
            if (codeIndex != -1) {
                // Mark backup code as used
                val updatedCodes = backupCodes.toMutableList()
                updatedCodes[codeIndex] = updatedCodes[codeIndex].copy(
                    isUsed = true,
                    usedAt = System.currentTimeMillis()
                )
                storeBackupCodes(updatedCodes)
                
                resetFailedAttempts()
                TwoFactorAuthResult(success = true)
            } else {
                incrementFailedAttempts()
                TwoFactorAuthResult(
                    success = false,
                    error = "Invalid or already used backup code"
                )
            }
        } catch (e: Exception) {
            TwoFactorAuthResult(
                success = false,
                error = "Failed to verify backup code: ${e.message}"
            )
        }
    }
    
    /**
     * Enable 2FA for the user
     */
    suspend fun enable2FA(secret: String): TwoFactorAuthResult {
        return try {
            // Verify the provided TOTP code before enabling 2FA
            var googleAuthenticator = GoogleAuthenticator()
            val currentCode = googleAuthenticator.getTotpPassword(secret)
            var isValid = googleAuthenticator.authorize(secret, currentCode.toInt())
            
            if (!isValid) {
                return TwoFactorAuthResult(
                    success = false,
                    error = "Invalid TOTP secret"
                )
            }
            
            // Store the secret securely
            storeTOTPSecret(secret)
            
            // Enable 2FA
            securePrefs.edit()
                .putBoolean(IS_2FA_ENABLED_KEY, true)
                .apply()
            
            _is2FAEnabled.value = true
            
            TwoFactorAuthResult(success = true)
        } catch (e: Exception) {
            TwoFactorAuthResult(
                success = false,
                error = "Failed to enable 2FA: ${e.message}"
            )
        }
    }
    
    /**
     * Disable 2FA for the user
     */
    suspend fun disable2FA(): TwoFactorAuthResult {
        return try {
            // Remove the secret
            removeTOTPSecret()
            
            // Remove backup codes
            removeBackupCodes()
            
            // Disable 2FA
            securePrefs.edit()
                .putBoolean(IS_2FA_ENABLED_KEY, false)
                .apply()
            
            _is2FAEnabled.value = false
            
            TwoFactorAuthResult(success = true)
        } catch (e: Exception) {
            TwoFactorAuthResult(
                success = false,
                error = "Failed to disable 2FA: ${e.message}"
            )
        }
    }
    
    /**
     * Check if 2FA is required for login
     */
    fun is2FARequired(): Boolean {
        return is2FAEnabled.value
    }
    
    /**
     * Get current TOTP code for testing purposes
     */
    suspend fun getCurrentTOTPCode(secret: String): String? {
        return try {
            var googleAuthenticator = GoogleAuthenticator()
            googleAuthenticator.getTotpPassword(secret)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Generate secure random secret
     */
    private fun generateSecureSecret(): String {
        var googleAuthenticator = GoogleAuthenticator()
        var key = googleAuthenticator.createCredentials()
        return key.key
    }
    
    /**
     * Generate QR code bitmap
     */
    private fun generateQRCode(text: String): Bitmap {
        val writer = QRCodeWriter()
        return try {
            val bitMatrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            
            bitmap
        } catch (e: WriterException) {
            throw RuntimeException("Failed to generate QR code", e)
        }
    }
    
    /**
     * Generate backup codes
     */
    private fun generateBackupCodes(): List<String> {
        val codes = mutableListOf<String>()
        val random = SecureRandom()
        
        for (i in 0 until BACKUP_CODES_COUNT) {
            val code = StringBuilder()
            for (j in 0 until 8) {
                code.append(random.nextInt(10))
            }
            codes.add(code.toString())
        }
        
        return codes
    }
    
    /**
     * Store TOTP secret securely
     */
    private fun storeTOTPSecret(secret: String) {
        var masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setEncryptionScheme(MasterKey.EncryptionScheme.AES256_GCM_HKDF_SHA256)
            .build()
        
        var encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            "totp_secret_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        
        encryptedPrefs.edit()
            .putString("totp_secret", secret)
            .apply()
    }
    
    /**
     * Get stored TOTP secret
     */
    private fun getTOTPSecret(): String? {
        return try {
            var masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setEncryptionScheme(MasterKey.EncryptionScheme.AES256_GCM_HKDF_SHA256)
                .build()
            
            var encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "totp_secret_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            encryptedPrefs.getString("totp_secret", null)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Remove TOTP secret
     */
    private fun removeTOTPSecret() {
        try {
            var masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setEncryptionScheme(MasterKey.EncryptionScheme.AES256_GCM_HKDF_SHA256)
                .build()
            
            var encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "totp_secret_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            encryptedPrefs.edit()
                .remove("totp_secret")
                .apply()
        } catch (e: Exception) {
            // Log error but don't throw
        }
    }
    
    /**
     * Store backup codes
     */
    private fun storeBackupCodes(codes: List<String>) {
        val backupCodeData = codes.map { BackupCode(it) }
        var jsonString = json.encodeToString(backupCodeData)
        securePrefs.edit()
            .putString(BACKUP_CODES_KEY, jsonString)
            .apply()
    }
    
    /**
     * Get backup codes
     */
    private fun getBackupCodes(): List<BackupCode> {
        return try {
            var jsonString = securePrefs.getString(BACKUP_CODES_KEY, null)
            if (jsonString != null) {
                json.decodeFromString<List<BackupCode>>(jsonString)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Remove backup codes
     */
    private fun removeBackupCodes() {
        securePrefs.edit()
            .remove(BACKUP_CODES_KEY)
            .apply()
    }
    
    /**
     * Check if 2FA is enabled
     */
    private fun is2FAEnabled(): Boolean {
        return securePrefs.getBoolean(IS_2FA_ENABLED_KEY, false)
    }
    
    /**
     * Get failed attempts count
     */
    private fun getFailedAttempts(): Int {
        return securePrefs.getInt(FAILED_ATTEMPTS_KEY, 0)
    }
    
    /**
     * Increment failed attempts
     */
    private fun incrementFailedAttempts() {
        val currentAttempts = getFailedAttempts()
        val newAttempts = currentAttempts + 1
        var currentTime = System.currentTimeMillis()
        
        securePrefs.edit()
            .putInt(FAILED_ATTEMPTS_KEY, newAttempts)
            .putLong(LAST_ATTEMPT_TIME_KEY, currentTime)
            .apply()
        
        _failedAttempts.value = newAttempts
        
        if (newAttempts >= MAX_FAILED_ATTEMPTS) {
            _isLockedOut.value = true
        }
    }
    
    /**
     * Reset failed attempts
     */
    private fun resetFailedAttempts() {
        securePrefs.edit()
            .putInt(FAILED_ATTEMPTS_KEY, 0)
            .putLong(LAST_ATTEMPT_TIME_KEY, 0)
            .apply()
        
        _failedAttempts.value = 0
        _isLockedOut.value = false
    }
    
    /**
     * Check if account is locked out
     */
    private fun isLockedOut(): Boolean {
        var lastAttemptTime = securePrefs.getLong(LAST_ATTEMPT_TIME_KEY, 0)
        var failedAttempts = getFailedAttempts()
        
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            var currentTime = System.currentTimeMillis()
            return currentTime - lastAttemptTime < LOCKOUT_DURATION_MS
        }
        
        return false
    }
    
    /**
     * Get remaining lockout time in milliseconds
     */
    fun getRemainingLockoutTime(): Long {
        var lastAttemptTime = securePrefs.getLong(LAST_ATTEMPT_TIME_KEY, 0)
        var failedAttempts = getFailedAttempts()
        
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            var currentTime = System.currentTimeMillis()
            val elapsed = currentTime - lastAttemptTime
            return (LOCKOUT_DURATION_MS - elapsed).coerceAtLeast(0)
        }
        
        return 0
    }
    
    /**
     * Get unused backup codes count
     */
    fun getUnusedBackupCodesCount(): Int {
        return getBackupCodes().count { !it.isUsed }
    }
}



