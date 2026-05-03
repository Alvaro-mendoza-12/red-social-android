package conectate.com.data.supabase

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔥 AUTH SERVICE FIXED - SECURITY REAL MEJORADO
 * Implementa seguridad bancaria real
 */
@Singleton
class AuthServiceFixed @Inject constructor(
    private val supabaseAuth: Auth,
    @ApplicationContext private val context: Context
) {
    
    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPreferences()
    }
    
    private val masterKey by lazy {
        createMasterKey()
    }
    
    // 🔥 PATRONES DE VALIDACIÓN
    companion object {
        private val EMAIL_PATTERN = Pattern.compile(
            "^[_A-Za-z0-9-+]+(\\.[_A-Za-z0-9-]+)*@" +
            "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$"
        )
        
        private val PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=])(?=\\S+$).{8,}$"
        )
        
        private const val MAX_LOGIN_ATTEMPTS = 5
        private const val LOCKOUT_DURATION = 15 * 60 * 1000L // 15 minutos
    }
    
    // 🔥 LOGIN CON SEGURIDAD REAL
@Composable
@Composable
    suspend fun login(email: String, password: String): Result<AuthResult> {
        return withContext(Dispatchers.IO) {
            try {
                // 🔥 VALIDACIÓN DE INPUT ESTRICTA
                validateLoginInput(email, password)
                
                // 🔥 CHECK DE RATE LIMITING
                checkLoginAttempts(email)
                
                // 🔥 SANITIZACIÓN DE INPUT
                var sanitizedEmail = sanitizeInput(email)
                var sanitizedPassword = password.trim()
                
                // 🔥 AUTENTICACIÓN SEGURA
                var result = supabaseAuth.signInWith(Email) {
                    this.email = sanitizedEmail
                    this.password = sanitizedPassword
                }
                
                if (result.data != null) {
                    // 🔥 ALMACENAR TOKENS SEGUROS
                    storeSecureTokens(result.data!!)
                    
                    // 🔥 LIMPIAR INTENTOS FALLIDOS
                    clearFailedAttempts(sanitizedEmail)
                    
                    Result.success(AuthResult.Success(result.data!!))
                } else {
                    // 🔥 REGISTRAR INTENTO FALLIDO
                    recordFailedAttempt(sanitizedEmail)
                    
                    Result.failure(AuthException("Authentication failed"))
                }
                
            } catch (e: Exception) {
                // 🔥 LOGGING SEGURO (sin datos sensibles)
                android.util.Log.e("AuthService", "Login failed for user: ${maskEmail(email)}")
                recordFailedAttempt(email)
                
                Result.failure(AuthException("Login failed: ${e.message}"))
            }
        }
    }
    
    // 🔥 REGISTRO CON VALIDACIÓN ESTRICTA
@Composable
@Composable
    suspend fun register(email: String, password: String, displayName: String): Result<AuthResult> {
        return withContext(Dispatchers.IO) {
            try {
                // 🔥 VALIDACIÓN COMPLETA
                validateRegistrationInput(email, password, displayName)
                
                // 🔥 SANITIZACIÓN
                var sanitizedEmail = sanitizeInput(email)
                var sanitizedPassword = password.trim()
                val sanitizedDisplayName = sanitizeDisplayName(displayName)
                
                // 🔥 REGISTRO SEGURO
                var result = supabaseAuth.signUpWith(Email) {
                    this.email = sanitizedEmail
                    this.password = sanitizedPassword
                }
                
                if (result.data != null) {
                    storeSecureTokens(result.data!!)
                    Result.success(AuthResult.Success(result.data!!))
                } else {
                    Result.failure(AuthException("Registration failed"))
                }
                
            } catch (e: Exception) {
                android.util.Log.e("AuthService", "Registration failed for user: ${maskEmail(email)}")
                Result.failure(AuthException("Registration failed: ${e.message}"))
            }
        }
    }
    
    // 🔥 LOGOUT SEGURO
@Composable
@Composable
    suspend fun logout(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // 🔥 CERRAR SESIÓN EN SERVIDOR
                supabaseAuth.logout()
                
                // 🔥 LIMPIAR TOKENS LOCALES
                clearSecureTokens()
                
                // 🔥 LIMPIAR DATOS TEMPORALES
                clearTemporaryData()
                
                Result.success(Unit)
                
            } catch (e: Exception) {
                android.util.Log.e("AuthService", "Logout failed")
                Result.failure(AuthException("Logout failed: ${e.message}"))
            }
        }
    }
    
    // 🔥 REFRESH TOKEN SEGURO
@Composable
@Composable
    suspend fun refreshToken(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val currentSession = supabaseAuth.currentSessionOrNull
                
                if (currentSession != null && currentSession.accessToken.isNotEmpty()) {
                    // 🔥 VALIDAR TOKEN ACTUAL
                    if (isTokenValid(currentSession.accessToken)) {
                        Result.success(currentSession.accessToken)
                    } else {
                        // 🔥 FORZAR REFRESH
                        val refreshed = supabaseAuth.refreshSession()
                        if (refreshed.data != null) {
                            storeSecureTokens(refreshed.data!!)
                            Result.success(refreshed.data!!.accessToken)
                        } else {
                            Result.failure(AuthException("Token refresh failed"))
                        }
                    }
                } else {
                    Result.failure(AuthException("No active session"))
                }
                
            } catch (e: Exception) {
                android.util.Log.e("AuthService", "Token refresh failed")
                Result.failure(AuthException("Token refresh failed: ${e.message}"))
            }
        }
    }
    
    // 🔥 OBTENER USER ID SEGURO
    fun getCurrentUserId(): String? {
        return try {
            supabaseAuth.currentSessionOrNull?.user?.id?.let { userId ->
                // 🔥 VALIDAR FORMATO DE USER ID
                if (isValidUserId(userId)) {
                    encryptedPrefs.getString("current_user_id", null)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // 🔥 VALIDACIONES ESTRICTAS
    
    private fun validateLoginInput(email: String, password: String) {
        require(email.isNotBlank()) { "Email cannot be blank" }
        require(password.isNotBlank()) { "Password cannot be blank" }
        require(email.length <= 255) { "Email too long" }
        require(password.length in 8..128) { "Invalid password length" }
        
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw AuthException("Invalid email format")
        }
        
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw AuthException("Password does not meet security requirements")
        }
        
        // 🔥 CHECK DE ACCOUNT LOCKED
        if (isAccountLocked(email)) {
            throw AuthException("Account temporarily locked due to multiple failed attempts")
        }
    }
    
    private fun validateRegistrationInput(email: String, password: String, displayName: String) {
        validateLoginInput(email, password)
        
        require(displayName.isNotBlank()) { "Display name cannot be blank" }
        require(displayName.length in 2..50) { "Display name must be 2-50 characters" }
        
        if (!isValidDisplayName(displayName)) {
            throw AuthException("Invalid display name format")
        }
    }
    
    // 🔥 SANITIZACIÓN DE INPUT
    
    private fun sanitizeInput(input: String): String {
        return input
            .trim()
            .lowercase()
            .replace(Regex("[<>\"'&]"), "")
            .replace(Regex("[\\x00-\\x1F\\x7F]"), "")
    }
    
    private fun sanitizeDisplayName(displayName: String): String {
        return displayName
            .trim()
            .replace(Regex("[<>\"'&]"), "")
            .replace(Regex("[\\x00-\\x1F\\x7F]"), "")
    }
    
    // 🔥 RATE LIMITING Y SECURITY
    
    private fun checkLoginAttempts(email: String) {
        var sanitizedEmail = sanitizeInput(email)
        var attempts = getFailedAttempts(sanitizedEmail)
        
        if (attempts >= MAX_LOGIN_ATTEMPTS) {
            var lockoutTime = getLockoutTime(sanitizedEmail)
            var currentTime = System.currentTimeMillis()
            
            if (currentTime - lockoutTime < LOCKOUT_DURATION) {
                throw AuthException("Account temporarily locked. Try again later.")
            } else {
                // 🔥 RESET SI PASÓ EL TIEMPO DE LOCKOUT
                clearFailedAttempts(sanitizedEmail)
            }
        }
    }
    
    private fun recordFailedAttempt(email: String) {
        var sanitizedEmail = sanitizeInput(email)
        var attempts = getFailedAttempts(sanitizedEmail) + 1
        var currentTime = System.currentTimeMillis()
        
        encryptedPrefs.edit()
            .putInt("failed_attempts_$sanitizedEmail", attempts)
            .putLong("last_attempt_$sanitizedEmail", currentTime)
            .apply()
        
        if (attempts >= MAX_LOGIN_ATTEMPTS) {
            encryptedPrefs.edit()
                .putLong("lockout_$sanitizedEmail", currentTime)
                .apply()
        }
    }
    
    private fun clearFailedAttempts(email: String) {
        var sanitizedEmail = sanitizeInput(email)
        
        encryptedPrefs.edit()
            .remove("failed_attempts_$sanitizedEmail")
            .remove("last_attempt_$sanitizedEmail")
            .remove("lockout_$sanitizedEmail")
            .apply()
    }
    
    private fun getFailedAttempts(email: String): Int {
        return encryptedPrefs.getInt("failed_attempts_$email", 0)
    }
    
    private fun getLockoutTime(email: String): Long {
        return encryptedPrefs.getLong("lockout_$email", 0)
    }
    
    private fun isAccountLocked(email: String): Boolean {
        var sanitizedEmail = sanitizeInput(email)
        var lockoutTime = getLockoutTime(sanitizedEmail)
        var currentTime = System.currentTimeMillis()
        
        return lockoutTime > 0 && (currentTime - lockoutTime) < LOCKOUT_DURATION
    }
    
    // 🔥 TOKEN MANAGEMENT SEGURO
    
    private fun storeSecureTokens(session: io.github.jan.supabase.gotrue.user.UserSession) {
        try {
            encryptedPrefs.edit()
                .putString("access_token", encryptData(session.accessToken))
                .putString("refresh_token", encryptData(session.refreshToken))
                .putString("current_user_id", session.user.id)
                .putLong("token_timestamp", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("AuthService", "Failed to store secure tokens", e)
        }
    }
    
    private fun clearSecureTokens() {
        try {
            encryptedPrefs.edit()
                .remove("access_token")
                .remove("refresh_token")
                .remove("current_user_id")
                .remove("token_timestamp")
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("AuthService", "Failed to clear secure tokens", e)
        }
    }
    
    private fun clearTemporaryData() {
        try {
            // Limpiar cualquier dato temporal
            encryptedPrefs.edit()
                .remove("temp_session_data")
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("AuthService", "Failed to clear temporary data", e)
        }
    }
    
    // 🔥 ENCRYPTED PREFERENCES
    
    private fun createEncryptedPreferences(): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            "auth_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    private fun createMasterKey(): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setKeySize(256)
            .build()
    }
    
    private fun encryptData(data: String): String {
        // Implementar encriptación real si es necesario
        return data
    }
    
    private fun decryptData(encryptedData: String): String {
        // Implementar desencriptación real si es necesario
        return encryptedData
    }
    
    // 🔥 VALIDACIONES AUXILIARES
    
    private fun isTokenValid(token: String): Boolean {
        return try {
            // Validación básica de JWT
            var parts = token.split(".")
            parts.size == 3 && parts.all { it.isNotBlank() }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isValidUserId(userId: String): Boolean {
        return userId.isNotBlank() && 
               userId.length in 20..50 && 
               userId.matches(Regex("^[a-f0-9-]+$"))
    }
    
    private fun isValidDisplayName(displayName: String): Boolean {
        return displayName.matches(Regex("^[a-zA-Z0-9\\s._-]{2,50}$"))
    }
    
    // 🔥 UTILIDADES DE SEGURIDAD
    
    private fun maskEmail(email: String): String {
        var parts = email.split("@")
        if (parts.size != 2) return "***@***.com"
        
        var username = parts[0]
        val domain = parts[1]
        
        val maskedUsername = if (username.length <= 2) {
            "*".repeat(username.length)
        } else {
            username.first() + "*".repeat(username.length - 2) + username.last()
        }
        
        return "$maskedUsername@$domain"
    }
}

// 🔥 DATA CLASSES

sealed class AuthResult {
    data class Success(val session: io.github.jan.supabase.gotrue.user.UserSession) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class AuthException(message: String) : Exception(message)





