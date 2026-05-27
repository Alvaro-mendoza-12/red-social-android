package conectate.com.auth.twoFactor

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Locale
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TwoFactorAuthManager private constructor(
    private val context: Context,
    private val sessionEvents: StateFlow<*>?,
    private val currentUserIdProvider: () -> String?,
    private val currentUserEmailProvider: () -> String?
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        authService: AuthService
    ) : this(
        context = context,
        sessionEvents = authService.currentSession,
        currentUserIdProvider = { authService.getCurrentUserId() },
        currentUserEmailProvider = { authService.getCurrentUserEmail() }
    )

    internal constructor(
        context: Context,
        currentUserId: String? = "test-user",
        currentUserEmail: String? = "test@example.com"
    ) : this(
        context = context,
        sessionEvents = null,
        currentUserIdProvider = { currentUserId },
        currentUserEmailProvider = { currentUserEmail }
    )

    data class TwoFactorAuthResult(
        val success: Boolean = false,
        val error: String? = null,
        val secret: String? = null,
        val qrCodeUrl: String? = null,
        val otpauthUrl: String? = null,
        val backupCodes: List<String> = emptyList()
    )

    private data class SecurePrefsState(
        val prefs: SharedPreferences?,
        val initializationError: Throwable?
    )

    companion object {
        private const val PREFS_NAME = "two_factor_secure_prefs"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L
        private const val ISSUER = "Conectate"
        private const val TOTP_DIGITS = 6
        private const val TOTP_PERIOD_SECONDS = 30L
        private const val TOTP_SECRET_BYTES = 20
        private const val TOTP_WINDOW_SIZE = 3
        private const val BACKUP_CODE_COUNT = 8
        private const val BACKUP_CODE_BOUND = 100_000_000
        private const val HMAC_ALGORITHM = "HmacSHA1"
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }

    private val secureRandom = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val securePrefsState by lazy { createSecurePrefsState() }
    private val prefsOrNull: SharedPreferences?
        get() = securePrefsState.prefs

    private val _failedAttempts = MutableStateFlow(0)
    val failedAttempts: StateFlow<Int> = _failedAttempts

    private val _isLockedOut = MutableStateFlow(false)
    val isLockedOut: StateFlow<Boolean> = _isLockedOut

    init {
        refreshState()
        sessionEvents?.let { events ->
            scope.launch {
                events.collect {
                    refreshState()
                }
            }
        }
    }

    fun is2FAEnabled(): Boolean = readCurrentBoolean("enabled", false)

    fun is2FARequired(): Boolean = is2FAEnabled()

    fun setup2FA() {
        refreshState()
    }

    fun verify2FA(code: String): Boolean {
        val secret = getStoredSecret() ?: return false
        return verifyTOTPCode(code, secret).success
    }

    fun getUnusedBackupCodesCount(): Int = getStoredBackupCodes().size

    fun getRemainingLockoutTime(): Long {
        val lockedUntil = readCurrentLong("locked_until", 0L)
        return (lockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun generateTOTPSecret(email: String): TwoFactorAuthResult {
        val accountLabel = email.trim().lowercase(Locale.US)
        if (accountLabel.isBlank()) {
            return TwoFactorAuthResult(error = "No se pudo generar 2FA sin un correo valido.")
        }

        return runCatching {
            val secret = generateBase32Secret()
            val backupCodes = generateBackupCodes()
            val otpAuthUrl = buildOtpAuthUrl(ISSUER, accountLabel, secret)
            val qrCodeUrl = buildQrCodeUrl(otpAuthUrl)

            persistPendingSetup(
                accountHint = accountLabel,
                secret = secret,
                backupCodes = backupCodes,
                qrCodeUrl = qrCodeUrl,
                otpAuthUrl = otpAuthUrl
            )

            refreshState()
            TwoFactorAuthResult(
                success = true,
                secret = secret,
                qrCodeUrl = qrCodeUrl,
                otpauthUrl = otpAuthUrl,
                backupCodes = backupCodes
            )
        }.getOrElse { error ->
            android.util.Log.e("TwoFactorAuthManager", "No se pudo generar la configuracion 2FA", error)
            TwoFactorAuthResult(error = error.message ?: "No se pudo generar el codigo 2FA.")
        }
    }

    fun enable2FA(secret: String): TwoFactorAuthResult {
        val identity = currentUserStorageKey() ?: return TwoFactorAuthResult(
            error = "No hay una cuenta activa para habilitar 2FA."
        )
        val cleanSecret = secret.trim()
        if (cleanSecret.isBlank()) {
            return TwoFactorAuthResult(error = "La clave secreta de 2FA no es valida.")
        }

        return runCatching {
            val pendingSecret = readForIdentity(identity, "pending_secret")
            val backupCodes = when {
                pendingSecret == cleanSecret -> getPendingBackupCodes(identity)
                else -> getStoredBackupCodes(identity)
            }

            prefsOrNull?.edit()
                ?.putBoolean(prefixed(identity, "enabled"), true)
                ?.putString(prefixed(identity, "secret"), cleanSecret)
                ?.putStringSet(prefixed(identity, "backup_codes"), backupCodes.toSet())
                ?.remove(prefixed(identity, "pending_secret"))
                ?.remove(prefixed(identity, "pending_backup_codes"))
                ?.remove(prefixed(identity, "pending_qr_url"))
                ?.remove(prefixed(identity, "pending_otpauth_url"))
                ?.apply()

            resetFailedAttempts(identity)
            refreshState()

            TwoFactorAuthResult(
                success = true,
                secret = cleanSecret,
                qrCodeUrl = readForIdentity(identity, "pending_qr_url"),
                otpauthUrl = readForIdentity(identity, "pending_otpauth_url"),
                backupCodes = backupCodes
            )
        }.getOrElse { error ->
            android.util.Log.e("TwoFactorAuthManager", "No se pudo habilitar 2FA", error)
            TwoFactorAuthResult(error = error.message ?: "No se pudo habilitar 2FA.")
        }
    }

    fun disable2FA(): TwoFactorAuthResult {
        val identity = currentUserStorageKey() ?: return TwoFactorAuthResult(
            error = "No hay una cuenta activa para deshabilitar 2FA."
        )

        return runCatching {
            prefsOrNull?.edit()
                ?.remove(prefixed(identity, "enabled"))
                ?.remove(prefixed(identity, "secret"))
                ?.remove(prefixed(identity, "backup_codes"))
                ?.remove(prefixed(identity, "pending_secret"))
                ?.remove(prefixed(identity, "pending_backup_codes"))
                ?.remove(prefixed(identity, "pending_qr_url"))
                ?.remove(prefixed(identity, "pending_otpauth_url"))
                ?.remove(prefixed(identity, "failed_attempts"))
                ?.remove(prefixed(identity, "locked_until"))
                ?.apply()
            refreshState()
            TwoFactorAuthResult(success = true)
        }.getOrElse { error ->
            android.util.Log.e("TwoFactorAuthManager", "No se pudo deshabilitar 2FA", error)
            TwoFactorAuthResult(error = error.message ?: "No se pudo deshabilitar 2FA.")
        }
    }

    fun verifyTOTPCode(code: String, secret: String): TwoFactorAuthResult {
        val identity = currentUserStorageKey()
        val remaining = getRemainingLockoutTime()
        if (remaining > 0L) {
            refreshState()
            return TwoFactorAuthResult(
                error = "2FA bloqueado temporalmente. Intenta de nuevo en ${remaining / 1000}s."
            )
        }

        val cleanCode = code.filter(Char::isDigit)
        if (cleanCode.length != 6) {
            return TwoFactorAuthResult(error = "Ingresa un codigo 2FA de 6 digitos.")
        }

        val authorized = verifyTotpWindow(secret.trim(), cleanCode)

        return if (authorized) {
            identity?.let(::resetFailedAttempts)
            refreshState()
            TwoFactorAuthResult(success = true, secret = secret.trim())
        } else {
            identity?.let(::registerFailedAttempt)
            refreshState()
            TwoFactorAuthResult(error = "El codigo 2FA no coincide.")
        }
    }

    fun verifyBackupCode(code: String): TwoFactorAuthResult {
        val identity = currentUserStorageKey()
        val remaining = getRemainingLockoutTime()
        if (remaining > 0L) {
            refreshState()
            return TwoFactorAuthResult(
                error = "2FA bloqueado temporalmente. Intenta de nuevo en ${remaining / 1000}s."
            )
        }

        val cleanCode = code.trim().uppercase(Locale.US)
        if (cleanCode.isBlank()) {
            return TwoFactorAuthResult(error = "Ingresa un codigo de respaldo valido.")
        }

        val storedCodes = getStoredBackupCodes()
        return if (storedCodes.any { it.equals(cleanCode, ignoreCase = true) }) {
            val updatedCodes = storedCodes.filterNot { it.equals(cleanCode, ignoreCase = true) }
            identity?.let { owner ->
                prefsOrNull?.edit()
                    ?.putStringSet(prefixed(owner, "backup_codes"), updatedCodes.toSet())
                    ?.apply()
                resetFailedAttempts(owner)
            }
            refreshState()
            TwoFactorAuthResult(success = true, backupCodes = updatedCodes)
        } else {
            identity?.let(::registerFailedAttempt)
            refreshState()
            TwoFactorAuthResult(error = "El codigo de respaldo no es valido.")
        }
    }

    fun getStoredSecret(): String? = readCurrentString("secret")

    fun getPendingSecret(): String? = readCurrentString("pending_secret")

    fun getPendingQrCodeUrl(): String? = readCurrentString("pending_qr_url")

    fun getPendingOtpAuthUrl(): String? = readCurrentString("pending_otpauth_url")

    fun getPendingBackupCodes(): List<String> {
        val identity = currentUserStorageKey() ?: return emptyList()
        return getPendingBackupCodes(identity)
    }

    private fun createSecurePrefsState(): SecurePrefsState = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        SecurePrefsState(prefs = prefs, initializationError = null)
    } catch (error: Throwable) {
        android.util.Log.e("TwoFactorAuthManager", "Fallo inicializando prefs cifradas de 2FA", error)
        try {
            val fallback = context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
            SecurePrefsState(fallback, error)
        } catch (fallbackError: Throwable) {
            SecurePrefsState(null, fallbackError)
        }
    }

    private fun persistPendingSetup(
        accountHint: String,
        secret: String,
        backupCodes: List<String>,
        qrCodeUrl: String,
        otpAuthUrl: String
    ) {
        val identity = currentUserStorageKey() ?: normalizeIdentity(accountHint)
        prefsOrNull?.edit()
            ?.putString(prefixed(identity, "pending_secret"), secret)
            ?.putStringSet(prefixed(identity, "pending_backup_codes"), backupCodes.toSet())
            ?.putString(prefixed(identity, "pending_qr_url"), qrCodeUrl)
            ?.putString(prefixed(identity, "pending_otpauth_url"), otpAuthUrl)
            ?.apply()
    }

    private fun registerFailedAttempt(identity: String) {
        val attempts = readIntForIdentity(identity, "failed_attempts", 0) + 1
        val shouldLock = attempts >= MAX_FAILED_ATTEMPTS
        prefsOrNull?.edit()
            ?.putInt(prefixed(identity, "failed_attempts"), attempts)
            ?.apply()
        if (shouldLock) {
            prefsOrNull?.edit()
                ?.putLong(prefixed(identity, "locked_until"), System.currentTimeMillis() + LOCKOUT_DURATION_MS)
                ?.apply()
        }
    }

    private fun resetFailedAttempts(identity: String) {
        prefsOrNull?.edit()
            ?.putInt(prefixed(identity, "failed_attempts"), 0)
            ?.putLong(prefixed(identity, "locked_until"), 0L)
            ?.apply()
    }

    private fun getStoredBackupCodes(identity: String = currentUserStorageKey().orEmpty()): List<String> {
        if (identity.isBlank()) return emptyList()
        return prefsOrNull
            ?.getStringSet(prefixed(identity, "backup_codes"), emptySet())
            ?.toList()
            ?.sorted()
            .orEmpty()
    }

    private fun getPendingBackupCodes(identity: String): List<String> {
        if (identity.isBlank()) return emptyList()
        return prefsOrNull
            ?.getStringSet(prefixed(identity, "pending_backup_codes"), emptySet())
            ?.toList()
            ?.sorted()
            .orEmpty()
    }

    private fun generateBase32Secret(): String {
        val bytes = ByteArray(TOTP_SECRET_BYTES)
        secureRandom.nextBytes(bytes)
        return encodeBase32(bytes)
    }

    private fun generateBackupCodes(): List<String> {
        return List(BACKUP_CODE_COUNT) {
            String.format(Locale.US, "%08d", secureRandom.nextInt(BACKUP_CODE_BOUND))
        }
    }

    private fun buildOtpAuthUrl(issuer: String, accountLabel: String, secret: String): String {
        val label = "${urlEncode(issuer)}:${urlEncode(accountLabel)}"
        return "otpauth://totp/$label" +
            "?secret=$secret" +
            "&issuer=${urlEncode(issuer)}" +
            "&algorithm=SHA1" +
            "&digits=$TOTP_DIGITS" +
            "&period=$TOTP_PERIOD_SECONDS"
    }

    private fun buildQrCodeUrl(otpAuthUrl: String): String {
        return "https://api.qrserver.com/v1/create-qr-code/?size=512x512&data=${urlEncode(otpAuthUrl)}"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun verifyTotpWindow(secret: String, cleanCode: String): Boolean {
        return runCatching {
            val currentCounter = currentTotpCounter()
            (-TOTP_WINDOW_SIZE..TOTP_WINDOW_SIZE).any { offset ->
                generateTotpCode(secret, currentCounter + offset) == cleanCode
            }
        }.getOrElse {
            android.util.Log.e("TwoFactorAuthManager", "Fallo verificando TOTP", it)
            false
        }
    }

    private fun currentTotpCounter(): Long {
        return (System.currentTimeMillis() / 1000L) / TOTP_PERIOD_SECONDS
    }

    private fun generateTotpCode(secret: String, counter: Long): String {
        val key = decodeBase32(secret)
        val counterBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array()
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        val hash = mac.doFinal(counterBytes)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 1_000_000
        return String.format(Locale.US, "%0${TOTP_DIGITS}d", otp)
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val encoded = StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                encoded.append(BASE32_ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1f])
                bitsLeft -= 5
            }
        }

        if (bitsLeft > 0) {
            encoded.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1f])
        }

        return encoded.toString()
    }

    private fun decodeBase32(secret: String): ByteArray {
        val normalized = secret
            .uppercase(Locale.US)
            .filter { it != '=' && !it.isWhitespace() }
        val output = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0

        normalized.forEach { char ->
            val value = BASE32_ALPHABET.indexOf(char)
            require(value >= 0) { "Caracter Base32 invalido en secreto TOTP." }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                output.write((buffer shr (bitsLeft - 8)) and 0xff)
                bitsLeft -= 8
            }
        }

        return output.toByteArray()
    }

    private fun refreshState() {
        val identity = currentUserStorageKey()
        if (identity.isNullOrBlank()) {
            _failedAttempts.value = 0
            _isLockedOut.value = false
            return
        }

        val remaining = (readLongForIdentity(identity, "locked_until", 0L) - System.currentTimeMillis())
            .coerceAtLeast(0L)
        if (remaining == 0L && readLongForIdentity(identity, "locked_until", 0L) > 0L) {
            resetFailedAttempts(identity)
        }

        _failedAttempts.value = readIntForIdentity(identity, "failed_attempts", 0)
        _isLockedOut.value = remaining > 0L
    }

    private fun currentUserStorageKey(): String? {
        val userId = currentUserIdProvider()
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
        if (!userId.isNullOrBlank()) return "uid_$userId"

        val email = currentUserEmailProvider()
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
        if (!email.isNullOrBlank()) return normalizeIdentity(email)

        return null
    }

    private fun normalizeIdentity(raw: String): String {
        return raw.trim().lowercase(Locale.US).replace(":", "_")
    }

    private fun prefixed(identity: String, key: String): String = "2fa_${identity}_$key"

    private fun readCurrentString(key: String): String? {
        val identity = currentUserStorageKey() ?: return null
        return readForIdentity(identity, key)
    }

    private fun readCurrentBoolean(key: String, defaultValue: Boolean): Boolean {
        val identity = currentUserStorageKey() ?: return defaultValue
        return prefsOrNull?.getBoolean(prefixed(identity, key), defaultValue) ?: defaultValue
    }

    private fun readCurrentLong(key: String, defaultValue: Long): Long {
        val identity = currentUserStorageKey() ?: return defaultValue
        return readLongForIdentity(identity, key, defaultValue)
    }

    private fun readForIdentity(identity: String, key: String): String? {
        return prefsOrNull?.getString(prefixed(identity, key), null)
    }

    private fun readLongForIdentity(identity: String, key: String, defaultValue: Long): Long {
        return prefsOrNull?.getLong(prefixed(identity, key), defaultValue) ?: defaultValue
    }

    private fun readIntForIdentity(identity: String, key: String, defaultValue: Int): Int {
        return prefsOrNull?.getInt(prefixed(identity, key), defaultValue) ?: defaultValue
    }
}
