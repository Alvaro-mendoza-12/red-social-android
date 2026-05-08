package conectate.com.config

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

/**
 * 🔥 SECRETS MANAGER - GESTIÓN SECRETA DE VARIABLES DE ENTORNO
 * Gestión segura de secrets, API keys y tokens sensibles
 * 
 * Este sistema maneja de forma segura:
 * - API keys y tokens
 * - Database credentials
 * - Third-party service keys
 * - Certificados y claves criptográficas
 */

@Singleton
class SecretsManager @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ConectateSecretsKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
    }
    
    // 🔥 KEYSTORE INITIALIZATION
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }
    
    private val secretKey: SecretKey by lazy {
        getOrCreateSecretKey()
    }
    
    private fun getOrCreateSecretKey(): SecretKey {
        return if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.getKey(KEY_ALIAS, null) as SecretKey
        } else {
            createSecretKey()
        }
    }
    
    private fun createSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .setUserAuthenticationRequired(false)
            .build()
        
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }
    
    // 🔥 ENCRYPTION/DECRYPTION
    private fun encrypt(data: String): String {
        var cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        var iv = cipher.iv
        val encryptedData = cipher.doFinal(data.toByteArray())
        
        val outputStream = ByteArrayOutputStream()
        outputStream.write(iv.size)
        outputStream.write(iv)
        outputStream.write(encryptedData.size)
        outputStream.write(encryptedData)
        
        return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
    }
    
    private fun decrypt(encryptedData: String): String {
        val decodedData = Base64.decode(encryptedData, Base64.DEFAULT)
        val inputStream = ByteArrayInputStream(decodedData)
        
        val ivSize = inputStream.read()
        var iv = ByteArray(ivSize)
        inputStream.read(iv)
        
        val encryptedSize = inputStream.read()
        val encrypted = ByteArray(encryptedSize)
        inputStream.read(encrypted)
        
        var cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        val decryptedData = cipher.doFinal(encrypted)
        return String(decryptedData)
    }
    
    // 🔥 SECRET STORAGE
    private val sharedPreferences = context.getSharedPreferences("ConectateSecrets", Context.MODE_PRIVATE)
    
    fun storeSecret(key: String, value: String): Boolean {
        return try {
            var encryptedValue = encrypt(value)
            sharedPreferences.edit()
                .putString(key, encryptedValue)
                .apply()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun getSecret(key: String): String? {
        return try {
            var encryptedValue = sharedPreferences.getString(key, null)
            encryptedValue?.let { decrypt(it) }
        } catch (e: Exception) {
            null
        }
    }
    
    fun removeSecret(key: String): Boolean {
        return try {
            sharedPreferences.edit()
                .remove(key)
                .apply()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // 🔥 PREDEFINED SECRETS
    enum class SecretKey(val key: String, val description: String) {
        SUPABASE_URL("supabase_url", "Supabase project URL"),
        SUPABASE_ANON_KEY("supabase_anon_key", "Supabase anonymous key"),
        SUPABASE_SERVICE_KEY("supabase_service_key", "Supabase service key"),
        FIREBASE_API_KEY("firebase_api_key", "Firebase API key"),
        FIREBASE_PROJECT_ID("firebase_project_id", "Firebase project ID"),
        FIREBASE_MESSAGING_SENDER_ID("firebase_messaging_sender_id", "Firebase messaging sender ID"),
        GOOGLE_CLIENT_ID("google_client_id", "Google OAuth client ID"),
        GOOGLE_CLIENT_SECRET("google_client_secret", "Google OAuth client secret"),
        FACEBOOK_APP_ID("facebook_app_id", "Facebook app ID"),
        FACEBOOK_APP_SECRET("facebook_app_secret", "Facebook app secret"),
        TWITTER_API_KEY("twitter_api_key", "Twitter API key"),
        TWITTER_API_SECRET("twitter_api_secret", "Twitter API secret"),
        STRIPE_PUBLISHABLE_KEY("stripe_publishable_key", "Stripe publishable key"),
        STRIPE_SECRET_KEY("stripe_secret_key", "Stripe secret key"),
        ANALYTICS_API_KEY("analytics_api_key", "Analytics API key"),
        CRASHLYTICS_API_KEY("crashlytics_api_key", "Crashlytics API key"),
        SENTRY_DSN("sentry_dsn", "Sentry DSN"),
        BUGSNAG_API_KEY("bugsnag_api_key", "Bugsnag API key"),
        MIXPANEL_TOKEN("mixpanel_token", "Mixpanel token"),
        AMPLITUDE_API_KEY("amplitude_api_key", "Amplitude API key"),
        ONE_SIGNAL_APP_ID("one_signal_app_id", "OneSignal app ID"),
        PUSH_NOTIFICATION_KEY("push_notification_key", "Push notification key"),
        ENCRYPTION_KEY("encryption_key", "Custom encryption key"),
        DATABASE_PASSWORD("database_password", "Database password"),
        API_SECRET_KEY("api_secret_key", "API secret key"),
        JWT_SECRET("jwt_secret", "JWT secret"),
        WEBHOOK_SECRET("webhook_secret", "Webhook secret"),
        CDN_ACCESS_KEY("cdn_access_key", "CDN access key"),
        STORAGE_ACCESS_KEY("storage_access_key", "Storage access key"),
        BACKUP_ENCRYPTION_KEY("backup_encryption_key", "Backup encryption key")
    }
    
    // 🔥 CONVENIENCE METHODS
    fun storeSupabaseConfig(url: String, anonKey: String, serviceKey: String): Boolean {
        return storeSecret(SecretKey.SUPABASE_URL.key, url) &&
                storeSecret(SecretKey.SUPABASE_ANON_KEY.key, anonKey) &&
                storeSecret(SecretKey.SUPABASE_SERVICE_KEY.key, serviceKey)
    }
    
    fun getSupabaseConfig(): Triple<String?, String?, String?> {
        return Triple(
            getSecret(SecretKey.SUPABASE_URL.key),
            getSecret(SecretKey.SUPABASE_ANON_KEY.key),
            getSecret(SecretKey.SUPABASE_SERVICE_KEY.key)
        )
    }
    
    fun storeFirebaseConfig(apiKey: String, projectId: String, messagingSenderId: String): Boolean {
        return storeSecret(SecretKey.FIREBASE_API_KEY.key, apiKey) &&
                storeSecret(SecretKey.FIREBASE_PROJECT_ID.key, projectId) &&
                storeSecret(SecretKey.FIREBASE_MESSAGING_SENDER_ID.key, messagingSenderId)
    }
    
    fun getFirebaseConfig(): Triple<String?, String?, String?> {
        return Triple(
            getSecret(SecretKey.FIREBASE_API_KEY.key),
            getSecret(SecretKey.FIREBASE_PROJECT_ID.key),
            getSecret(SecretKey.FIREBASE_MESSAGING_SENDER_ID.key)
        )
    }
    
    // 🔥 VALIDATION
    fun validateSecrets(): SecretsValidationResult {
        var missingSecrets = mutableListOf<String>()
        var invalidSecrets = mutableListOf<String>()
        
        // Validar secrets críticos
        val criticalSecrets = listOf(
            SecretKey.SUPABASE_URL,
            SecretKey.SUPABASE_ANON_KEY,
            SecretKey.FIREBASE_API_KEY,
            SecretKey.FIREBASE_PROJECT_ID
        )
        
        criticalSecrets.forEach { secretKey ->
            var value = getSecret(secretKey.key)
            when {
                value == null -> missingSecrets.add(secretKey.description)
                value.isBlank() -> invalidSecrets.add(secretKey.description)
            }
        }
        
        return SecretsValidationResult(
            isValid = missingSecrets.isEmpty() && invalidSecrets.isEmpty(),
            missingSecrets = missingSecrets,
            invalidSecrets = invalidSecrets,
            totalSecretsStored = getAllStoredSecrets().size
        )
    }
    
    // 🔥 UTILITIES
    fun getAllStoredSecrets(): Map<String, Boolean> {
        val allKeys = SecretKey.values()
        return allKeys.associate { secretKey ->
            secretKey.key to (getSecret(secretKey.key) != null)
        }
    }
    
    fun clearAllSecrets(): Boolean {
        return try {
            val editor = sharedPreferences.edit()
            SecretKey.values().forEach { secretKey ->
                editor.remove(secretKey.key)
            }
            editor.apply()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun exportSecretsMetadata(): Map<String, Any> {
        return mapOf(
            "totalSecrets" to SecretKey.values().size,
            "storedSecrets" to getAllStoredSecrets().values.count { it },
            "encryptionEnabled" to true,
            "keyStoreType" to ANDROID_KEYSTORE,
            "keyAlias" to KEY_ALIAS,
            "keySize" to KEY_SIZE,
            "transformation" to TRANSFORMATION
        )
    }
    
    // 🔥 FLOW PROVIDERS
    fun getSecretsValidationFlow(): Flow<SecretsValidationResult> = flow {
        emit(validateSecrets())
    }
    
    fun getStoredSecretsFlow(): Flow<Map<String, Boolean>> = flow {
        emit(getAllStoredSecrets())
    }
}

// 🔥 DATA MODELS

data class SecretsValidationResult(
    val isValid: Boolean,
    val missingSecrets: List<String>,
    val invalidSecrets: List<String>,
    val totalSecretsStored: Int
)

// 🔥 SECRETS PROVIDER

@Singleton
class SecretsProvider @Inject constructor(
    private val secretsManager: SecretsManager
) {
    
    fun getSecret(key: String): String? {
        return secretsManager.getSecret(key)
    }
    
    fun storeSecret(key: String, value: String): Boolean {
        return secretsManager.storeSecret(key, value)
    }
    
    fun validateSecrets(): SecretsValidationResult {
        return secretsManager.validateSecrets()
    }
    
    fun getSupabaseConfig(): Triple<String?, String?, String?> {
        return secretsManager.getSupabaseConfig()
    }
    
    fun getFirebaseConfig(): Triple<String?, String?, String?> {
        return secretsManager.getFirebaseConfig()
    }
    
    fun getAllStoredSecrets(): Map<String, Boolean> {
        return secretsManager.getAllStoredSecrets()
    }
    
    fun clearAllSecrets(): Boolean {
        return secretsManager.clearAllSecrets()
    }
}

// 🔥 IMPORTANTE: GCMParameterSpec
// Nota: GCMParameterSpec no está disponible en Android API level estándar
// En producción, deberías usar IvParameterSpec con el IV generado



