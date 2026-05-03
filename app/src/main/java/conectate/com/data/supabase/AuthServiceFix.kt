package conectate.com.data.supabase

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔥 FIX CRÍTICO: Seguridad en AuthService
 * Implementa encriptación robusta y manejo seguro de tokens
 */
@Singleton
class SecureAuthService @Inject constructor(
    @ApplicationContext private val context: Context,
    val baseAuthService: AuthService
) {
    
    // 🔥 MASTER KEY PARA ENCRIPTACIÓN
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    // 🔥 PREFERENCES ENCRIPTADAS
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "secure_auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    /**
     * 🔥 LOGIN SEGURO CON ENCRIPTACIÓN
     */
    suspend fun loginSecure(
        email: String,
        password: String
    ): Result<AuthSession> = withContext(Dispatchers.IO) {
        try {
            // 🔥 VALIDAR INPUTS
            if (email.isBlank() || password.isBlank()) {
                return@withContext Result.failure(Exception("Email y contraseña son requeridos"))
            }
            
            if (!isValidEmail(email)) {
                return@withContext Result.failure(Exception("Email inválido"))
            }
            
            if (password.length < 6) {
                return@withContext Result.failure(Exception("La contraseña debe tener al menos 6 caracteres"))
            }
            
            // 🔥 EJECUTAR LOGIN BASE
            val result = baseAuthService.login(email, password)
            
            result.onSuccess { session ->
                // 🔥 GUARDAR TOKEN SEGURO
                storeTokenSecure(session.accessToken)
                storeRefreshTokenSecure(session.refreshToken)
                
                // 🔥 GUARDAR METADATOS SEGUROS
                storeUserMetadataSecure(email, session.user.id)
            }
            
            return@withContext result
            
        } catch (e: Exception) {
            // 🔥 LOG SIN EXPOSICIÓN DE DATOS SENSIBLES
            android.util.Log.e("SecureAuthService", "Login error: ${e.javaClass.simpleName}")
            return@withContext Result.failure(Exception("Error en el login", e))
        }
    }
    
    /**
     * 🔥 REGISTRO SEGURO
     */
    suspend fun registerSecure(
        email: String,
        password: String,
        displayName: String?
    ): Result<AuthSession> = withContext(Dispatchers.IO) {
        try {
            // 🔥 VALIDACIONES ESTRICTAS
            if (!isValidEmail(email)) {
                return@withContext Result.failure(Exception("Email inválido"))
            }
            
            if (!isStrongPassword(password)) {
                return@withContext Result.failure(Exception("La contraseña debe tener al menos 8 caracteres, una mayúscula, una minúscula y un número"))
            }
            
            if (displayName.isNullOrBlank() || displayName.length < 2) {
                return@withContext Result.failure(Exception("El nombre debe tener al menos 2 caracteres"))
            }
            
            // 🔥 EJECUTAR REGISTRO BASE
            val result = baseAuthService.register(email, password, displayName)
            
            result.onSuccess { session ->
                // 🔥 GUARDAR TOKEN SEGURO
                storeTokenSecure(session.accessToken)
                storeRefreshTokenSecure(session.refreshToken)
            }
            
            return@withContext result
            
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Error en el registro", e))
        }
    }
    
    /**
     * 🔥 REFRESH TOKEN SEGURO
     */
    suspend fun refreshTokenSecure(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val refreshToken = getRefreshTokenSecure()
                ?: return@withContext Result.failure(Exception("No refresh token available"))
            
            val result = baseAuthService.refreshToken(refreshToken)
            
            result.onSuccess { newToken ->
                // 🔥 ACTUALIZAR TOKEN SEGURO
                storeTokenSecure(newToken)
            }
            
            return@withContext result
            
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Error refrescando token", e))
        }
    }
    
    /**
     * 🔥 LOGOUT SEGURO
     */
    suspend fun logoutSecure(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 🔥 LIMPIAR TOKENS
            clearAllTokens()
            
            // 🔥 EJECUTAR LOGOUT BASE
            return@withContext baseAuthService.logout()
            
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Error en el logout", e))
        }
    }
    
    /**
     * 🔥 OBTENER TOKEN SEGURO
     */
    fun getCurrentTokenSecure(): String? {
        return encryptedPrefs.getString("access_token", null)
    }
    
    /**
     * 🔥 VERIFICAR SESIÓN ACTIVA
     */
    fun isSessionActive(): Boolean {
        return getCurrentTokenSecure() != null
    }
    
    // 🔥 MÉTODOS PRIVADOS DE ALMACENAMIENTO SEGURO
    
    private fun storeTokenSecure(token: String) {
        encryptedPrefs.edit()
            .putString("access_token", token)
            .apply()
    }
    
    private fun storeRefreshTokenSecure(token: String) {
        encryptedPrefs.edit()
            .putString("refresh_token", token)
            .apply()
    }
    
    private fun storeUserMetadataSecure(email: String, userId: String) {
        encryptedPrefs.edit()
            .putString("user_email", email)
            .putString("user_id", userId)
            .putLong("login_time", System.currentTimeMillis())
            .apply()
    }
    
    private fun getRefreshTokenSecure(): String? {
        return encryptedPrefs.getString("refresh_token", null)
    }
    
    private fun clearAllTokens() {
        encryptedPrefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("user_email")
            .remove("user_id")
            .remove("login_time")
            .apply()
    }
    
    // 🔥 VALIDACIONES DE SEGURIDAD
    
    private fun isValidEmail(email: String): Boolean {
        val emailPattern = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        return email.matches(emailPattern.toRegex())
    }
    
    private fun isStrongPassword(password: String): Boolean {
        return password.length >= 8 &&
                password.any { it.isUpperCase() } &&
                password.any { it.isLowerCase() } &&
                password.any { it.isDigit() }
    }
    
    /**
     * 🔥 DETECCIÓN DE TAMPERING
     */
    fun detectTampering(): Boolean {
        try {
            val loginTime = encryptedPrefs.getLong("login_time", 0)
            val currentTime = System.currentTimeMillis()
            
            // 🔥 VERIFICAR TIEMPO DE SESIÓN (24 horas máximo)
            if (loginTime > 0 && (currentTime - loginTime) > 24 * 60 * 60 * 1000) {
                return true
            }
            
            // 🔥 VERIFICAR INTEGRIDAD DE TOKENS
            val token = getCurrentTokenSecure()
            if (token != null && !isValidTokenFormat(token)) {
                return true
            }
            
            return false
        } catch (e: Exception) {
            return true // Si hay error, asumir tampering
        }
    }
    
    private fun isValidTokenFormat(token: String): Boolean {
        // 🔥 VALIDAR FORMATO JWT BÁSICO
        return token.split(".").size == 3
    }
    
    /**
     * 🔥 FORZAR LOGOUT POR SEGURIDAD
     */
    suspend fun forceLogoutForSecurity(): Result<Unit> {
        return try {
            clearAllTokens()
            baseAuthService.logout()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Error en logout de seguridad", e))
        }
    }
    
    /**
     * 🔥 OBTENER METADATOS DE USUARIO SEGUROS
     */
    fun getUserMetadataSecure(): UserMetadata? {
        return try {
            val email = encryptedPrefs.getString("user_email", null)
            val userId = encryptedPrefs.getString("user_id", null)
            val loginTime = encryptedPrefs.getLong("login_time", 0)
            
            if (email != null && userId != null) {
                UserMetadata(email, userId, loginTime)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

data class UserMetadata(
    val email: String,
    val userId: String,
    val loginTime: Long
)

/**
 * 🔥 WRAPPER PARA AUTHSERVICE CON SEGURIDAD
 */
class SecureAuthServiceWrapper @Inject constructor(
    private val secureAuthService: SecureAuthService
) : AuthService by secureAuthService.baseAuthService {
    
    override suspend fun login(email: String, password: String): Result<AuthSession> {
        return secureAuthService.loginSecure(email, password)
    }
    
    override suspend fun register(email: String, password: String, displayName: String?): Result<AuthSession> {
        return secureAuthService.registerSecure(email, password, displayName)
    }
    
    override suspend fun logout(): Result<Unit> {
        return secureAuthService.logoutSecure()
    }
    
    override suspend fun refreshToken(): Result<String> {
        return secureAuthService.refreshTokenSecure()
    }
    
    override fun getCurrentUserId(): String? {
        return secureAuthService.getUserMetadataSecure()?.userId
    }
    
    override fun getCurrentUserEmail(): String? {
        return secureAuthService.getUserMetadataSecure()?.email
    }
}
