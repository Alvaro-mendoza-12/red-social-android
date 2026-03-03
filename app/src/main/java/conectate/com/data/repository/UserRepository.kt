package conectate.com.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import conectate.com.data.model.User
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.filter.*
import io.github.jan.supabase.realtime.*
import javax.inject.Inject
import javax.inject.Singleton

data class BlockRelationship(
    val blockedByMe: Boolean = false,
    val blockedMe: Boolean = false
) {
    val isBlocked: Boolean get() = blockedByMe || blockedMe
}

@Singleton
class UserRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val storageService: StorageService,
    private val userDao: conectate.com.data.local.dao.UserDao,
    @ApplicationContext private val context: Context
) {
    @Volatile
    private var blockedMentionCacheUserId: String? = null

    @Volatile
    private var blockedMentionCacheAtMs: Long = 0L

    @Volatile
    private var blockedMentionCacheIds: Set<String> = emptySet()

    private val blockedMentionCacheTtlMs = 30_000L
    private val avatarMaxBytes = 50L * 1024 * 1024

    // ─────────────────────────────────────────────
    // GET USER BY ID
    // ─────────────────────────────────────────────
    suspend fun getUserById(userId: String): Result<User?> {
        return try {
            val token = authService.getAccessToken()
            val currentUserId = authService.getCurrentUserId()

            // Try network if token exists
            if (token != null) {
                try {
                    val response = supabaseConfig.httpClient.get(
                        "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
                    ) {
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Authorization", "Bearer $token")
                        parameter("id", "eq.$userId")
                        parameter("select", "*") // Explicit select
                        parameter("limit", 1)
                    }

                    if (response.status.isSuccess()) {
                        val users = response.body<List<User>>()
                        val user = users.firstOrNull()
                        
                        android.util.Log.d("UserRepository", "Fetched user $userId: ${user?.displayName}")

                        if (user != null) {
                            // Persistent Room Cache
                            userDao.insertUser(conectate.com.data.local.entity.UserEntity.fromDomain(user))
                            
                            // Legacy SharedPreferences Cache (Current User Only)
                            if (userId == currentUserId) {
                                saveProfileLocally(user)
                            }
                        }
                        
                        return Result.success(user)
                    } else {
                         android.util.Log.e("UserRepository", "Fetch failed: ${response.status}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("UserRepository", "Network fetch failed", e)
                }
            }

            // Fallback to cache (Any user)
            val cachedEntity = userDao.getUser(userId)
            if (cachedEntity != null) {
                android.util.Log.d("UserRepository", "Returning cached profile from DAO")
                return Result.success(cachedEntity.toDomain())
            }

            // Fallback to legacy prefs for current user
            if (userId == currentUserId) {
                val cachedUser = getLocalProfile()
                if (cachedUser != null) {
                    return Result.success(cachedUser)
                }
            }
            
            if (token == null) Result.failure(Exception("No access token and no cache"))
            else Result.failure(Exception("Error loading profile"))
        } catch (e: Exception) {
            // Try cache on exception too
             try {
                val cachedEntity = userDao.getUser(userId)
                if (cachedEntity != null) {
                    return Result.success(cachedEntity.toDomain())
                }
            } catch (e2: Exception) { }
            
            Result.failure(e)
        }
    }

    private fun saveProfileLocally(user: User) {
        try {
            val prefs = context.getSharedPreferences("user_cache", Context.MODE_PRIVATE)
            val json = Json.encodeToString(User.serializer(), user)
            prefs.edit().putString("cached_profile", json).apply()
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Failed to cache profile", e)
        }
    }

    private fun getLocalProfile(): User? {
        return try {
            val prefs = context.getSharedPreferences("user_cache", Context.MODE_PRIVATE)
            val json = prefs.getString("cached_profile", null)
            if (json != null) {
                Json { ignoreUnknownKeys = true }.decodeFromString(User.serializer(), json)
            } else null
        } catch (e: Exception) {
             null
        }
    }

    /**
     * Clear all locally cached user profile data.
     */
    fun clearData() {
        android.util.Log.w("UserRepository", "CLEAR DATA: Purging local profile cache...")
        val prefs = context.getSharedPreferences("user_cache", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    suspend fun getAdditionalLoginEmails(): Result<List<String>> {
        return try {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))

            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profile_login_emails") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("user_id", "eq.$userId")
                parameter("select", "email")
                parameter("order", "created_at.asc")
            }

            if (response.status.isSuccess()) {
                val rows = response.body<List<JsonObject>>()
                val emails = rows.mapNotNull { row ->
                    row["email"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                }
                Result.success(emails.distinct())
            } else {
                Result.failure(Exception("No se pudo cargar correos adicionales (${response.status.value})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addAdditionalLoginEmail(email: String): Result<Unit> {
        return try {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            val normalized = email.trim().lowercase()

            val payload = buildJsonObject {
                put("user_id", userId)
                put("email", normalized)
            }

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/profile_login_emails") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                header("Prefer", "return=minimal")
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                val errorMessage = when {
                    body.contains("MAX_ADDITIONAL_EMAILS_REACHED", ignoreCase = true) ->
                        "Solo puedes agregar hasta 3 correos adicionales."
                    body.contains("PRIMARY_EMAIL_NOT_ALLOWED", ignoreCase = true) ->
                        "No puedes agregar tu correo principal como adicional."
                    body.contains("EMAIL_ALREADY_IN_USE", ignoreCase = true) ->
                        "Ese correo ya está en uso por otra cuenta."
                    body.contains("duplicate key", ignoreCase = true) || body.contains("23505") ->
                        "Ese correo ya está en uso."
                    else -> "No se pudo agregar el correo (${response.status.value})."
                }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeAdditionalLoginEmail(email: String): Result<Unit> {
        return try {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            val normalized = email.trim().lowercase()

            val response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/profile_login_emails") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("user_id", "eq.$userId")
                parameter("email", "eq.$normalized")
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("No se pudo eliminar el correo (${response.status.value})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUsersByIds(userIds: List<String>): Result<List<User>> {
        if (userIds.isEmpty()) return Result.success(emptyList())
        return try {
            val token = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("id", "in.(${userIds.joinToString(",")})")
            }

            if (response.status.isSuccess()) {
                val users = response.body<List<User>>()
                // Save all to Room
                userDao.insertUsers(users.map { conectate.com.data.local.entity.UserEntity.fromDomain(it) })
                Result.success(users)
            } else {
                Result.failure(Exception("Error loading profiles"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────
    // CREATE PROFILE (AFTER SIGNUP)
    // ─────────────────────────────────────────────
    suspend fun createProfile(user: User, privacyPolicyAccepted: Boolean = false): Result<Unit> {
        return try {
            val token = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            val body = buildJsonObject {
                put("id", JsonPrimitive(user.id))
                put("email", JsonPrimitive(user.email))
                user.username?.let { put("username", JsonPrimitive(it)) }
                user.full_name?.let { put("full_name", JsonPrimitive(it)) }
                user.avatar_url?.let { put("avatar_url", JsonPrimitive(it)) }
                user.bio?.let { put("bio", JsonPrimitive(it)) }
                put("privacy_policy_accepted", JsonPrimitive(privacyPolicyAccepted))
                if (privacyPolicyAccepted) {
                    put("privacy_policy_accepted_at", JsonPrimitive(java.time.OffsetDateTime.now().toString()))
                }
            }

            var response = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                header("Prefer", "return=minimal")
                setBody(body)
            }

            if (!response.status.isSuccess() && privacyPolicyAccepted) {
                val err = runCatching { response.bodyAsText() }.getOrDefault("")
                val missingPrivacyColumn =
                    err.contains("privacy_policy_accepted", ignoreCase = true) &&
                        err.contains("does not exist", ignoreCase = true)
                if (missingPrivacyColumn) {
                    val fallbackBody = buildJsonObject {
                        put("id", JsonPrimitive(user.id))
                        put("email", JsonPrimitive(user.email))
                        user.username?.let { put("username", JsonPrimitive(it)) }
                        user.full_name?.let { put("full_name", JsonPrimitive(it)) }
                        user.avatar_url?.let { put("avatar_url", JsonPrimitive(it)) }
                        user.bio?.let { put("bio", JsonPrimitive(it)) }
                    }
                    response = supabaseConfig.httpClient.post(
                        "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
                    ) {
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Authorization", "Bearer $token")
                        header("Content-Type", "application/json")
                        header("Prefer", "return=minimal")
                        setBody(fallbackBody)
                    }
                }
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val err = runCatching { response.body<String>() }.getOrDefault("")
                Result.failure(Exception("Create profile failed ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun hasAcceptedPrivacyPolicy(userId: String? = authService.getCurrentUserId()): Result<Boolean> {
        return try {
            val uid = userId ?: return Result.failure(Exception("Usuario no identificado"))
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("id", "eq.$uid")
                parameter("select", "privacy_policy_accepted")
                parameter("limit", 1)
            }

            if (!response.status.isSuccess()) {
                val err = runCatching { response.bodyAsText() }.getOrDefault("")
                val missingPrivacyColumn =
                    err.contains("privacy_policy_accepted", ignoreCase = true) &&
                        err.contains("does not exist", ignoreCase = true)
                if (missingPrivacyColumn) return Result.success(true)
                return Result.failure(Exception("No se pudo validar políticas (${response.status.value})"))
            }

            val rows = response.body<List<JsonObject>>()
            val accepted = rows.firstOrNull()
                ?.get("privacy_policy_accepted")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.equals("true", ignoreCase = true) == true
            Result.success(accepted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acceptPrivacyPolicy(userId: String? = authService.getCurrentUserId()): Result<Unit> {
        return try {
            val uid = userId ?: return Result.failure(Exception("Usuario no identificado"))
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val body = buildJsonObject {
                put("privacy_policy_accepted", JsonPrimitive(true))
                put("privacy_policy_accepted_at", JsonPrimitive(java.time.OffsetDateTime.now().toString()))
            }
            val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                parameter("id", "eq.$uid")
                setBody(body)
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val err = runCatching { response.bodyAsText() }.getOrDefault("")
                Result.failure(Exception("No se pudo aceptar políticas (${response.status.value}) $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserByEmail(email: String): Result<User?> {
        return try {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("email", "eq.$email")
                parameter("select", "*")
                parameter("limit", 1)
            }
            if (response.status.isSuccess()) {
                val users = response.body<List<User>>()
                Result.success(users.firstOrNull())
            } else Result.failure(Exception("Fetch failed ${response.status}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getUserByPhone(phone: String): Result<User?> {
        return try {
            val normalizedPhone = phone.trim().replace(Regex("[^0-9+]"), "")
            if (normalizedPhone.isBlank()) return Result.success(null)

            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val directResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("phone", "eq.$normalizedPhone")
                parameter("select", "*")
                parameter("limit", 1)
            }
            if (directResponse.status.isSuccess()) {
                val users = directResponse.body<List<User>>()
                if (users.isNotEmpty()) return Result.success(users.first())
            } else {
                return Result.failure(Exception("Fetch failed ${directResponse.status}"))
            }

            // Fallback when DB stores phone without leading "+"
            if (normalizedPhone.startsWith("+")) {
                val withoutPlus = normalizedPhone.removePrefix("+")
                val fallbackResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Authorization", "Bearer $token")
                    parameter("phone", "eq.$withoutPlus")
                    parameter("select", "*")
                    parameter("limit", 1)
                }
                if (fallbackResponse.status.isSuccess()) {
                    val users = fallbackResponse.body<List<User>>()
                    return Result.success(users.firstOrNull())
                }
                return Result.failure(Exception("Fetch failed ${fallbackResponse.status}"))
            }

            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserByUsername(username: String): Result<User?> {
        return try {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("username", "eq.$username")
                parameter("select", "*")
                parameter("limit", 1)
            }
            if (response.status.isSuccess()) {
                val users = response.body<List<User>>()
                Result.success(users.firstOrNull())
            } else Result.failure(Exception("Fetch failed ${response.status}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─────────────────────────────────────────────
    // UPDATE PROFILE
    // ─────────────────────────────────────────────
    suspend fun updateProfile(
        userId: String,
        request: UpdateProfileRequest
    ): Result<Unit> {
        return try {
            val token = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            // ✅ ENFORCE 7-DAY NAME CHANGE COOLDOWN
            if (request.display_name != null) {
                // Get current user profile to check last_name_change
                val userResult = getUserById(userId)
                if (userResult.isSuccess) {
                    val user = userResult.getOrNull()
                    val lastNameChange = user?.last_name_change

                    if (lastNameChange != null) {
                        try {
                            val lastChangeDate = java.time.LocalDateTime.parse(lastNameChange)
                            val now = java.time.LocalDateTime.now()
                            val daysSinceChange = java.time.Duration.between(lastChangeDate, now).toDays()

                            if (daysSinceChange < 7) {
                                val daysRemaining = 7 - daysSinceChange
                                return Result.failure(
                                    Exception("Puedes cambiar tu nombre en $daysRemaining días")
                                )
                            }
                        } catch (e: Exception) {
                            // If parsing fails, allow the change
                            android.util.Log.w("UserRepository", "Failed to parse last_name_change", e)
                        }
                    }
                }
            }

            // Build update request with last_name_change if name is being updated
            val finalRequest = if (request.display_name != null) {
                request.copy(last_name_change = java.time.LocalDateTime.now().toString())
            } else {
                request
            }

            // 🔥 LOG REQUEST FOR DEBUGGING
            val jsonBody = kotlinx.serialization.json.Json.encodeToString(
                UpdateProfileRequest.serializer(), 
                finalRequest
            )
            android.util.Log.d("UserRepository", "Update profile request: $jsonBody")

            val response = supabaseConfig.httpClient.patch(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                parameter("id", "eq.$userId")
                setBody(finalRequest)
            }

            if (response.status.isSuccess()) {
                val responseBody = response.body<String>()
                android.util.Log.d("UserRepository", "Profile updated successfully: $responseBody")
                
                // ✅ SYNC METADATA TO PREVENT RESET ON RE-LOGIN
                authService.updateUserMetadata(
                    fullName = finalRequest.display_name, 
                    avatarUrl = finalRequest.avatar_url
                )
                
                Result.success(Unit)
            } else {
                val err = response.body<String>()
                android.util.Log.e("UserRepository", "Update failed ${response.status}: $err")
                Result.failure(Exception("Update failed ${response.status}: $err"))
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Update profile exception", e)
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────
    // UPLOAD PROFILE IMAGE
    // ─────────────────────────────────────────────
    suspend fun uploadProfileImage(uri: Uri): Result<String> {
        return uploadImage(uri, "avatars", "_profile")
    }

    // ─────────────────────────────────────────────
    // UPLOAD COVER IMAGE
    // ─────────────────────────────────────────────
    suspend fun uploadCoverImage(uri: Uri): Result<String> {
        return uploadImage(uri, "avatars", "_cover")
    }

    // ─────────────────────────────────────────────
    // UPLOAD IMAGE (generic)
    // ─────────────────────────────────────────────
    private suspend fun uploadImage(
        uri: Uri,
        bucket: String,
        suffixLabel: String
    ): Result<String> {
        return try {
            val userId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            val bytes = context.contentResolver
                .openInputStream(uri)
                ?.use { it.readBytes() }
                ?: return Result.failure(Exception("Cannot read file"))

            val allowedImageMimeTypes = setOf("image/jpeg", "image/png", "image/webp")
            val hintedMime = context.contentResolver.getType(uri)
                ?.substringBefore(";")
                ?.trim()
                ?.lowercase()
            val inferredMime = inferImageMimeType(bytes)
            val finalMime = when {
                !hintedMime.isNullOrBlank() && allowedImageMimeTypes.contains(hintedMime) -> hintedMime
                !inferredMime.isNullOrBlank() -> inferredMime
                else -> null
            } ?: return Result.failure(Exception("Formato de imagen no soportado. Usa JPG, PNG o WEBP."))

            val preparedPayload = prepareImagePayloadForUpload(
                uri = uri,
                originalBytes = bytes,
                bucket = bucket,
                detectedMimeType = finalMime
            ).getOrElse { error ->
                return Result.failure(error)
            }

            val fileName = "$userId/${UUID.randomUUID()}${suffixLabel}.${preparedPayload.extension}"

            val upload = storageService.uploadFile(
                bucket,
                fileName,
                preparedPayload.bytes,
                preparedPayload.mimeType
            )

            if (upload.isSuccess) {
                Result.success(storageService.getPublicUrl(bucket, fileName))
            } else {
                Result.failure(upload.exceptionOrNull() ?: Exception("Upload failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class PreparedImagePayload(
        val bytes: ByteArray,
        val mimeType: String,
        val extension: String
    )

    private fun prepareImagePayloadForUpload(
        uri: Uri,
        originalBytes: ByteArray,
        bucket: String,
        detectedMimeType: String
    ): Result<PreparedImagePayload> {
        val defaultPayload = PreparedImagePayload(
            bytes = originalBytes,
            mimeType = detectedMimeType,
            extension = extensionForMime(detectedMimeType)
        )
        if (bucket != "avatars") return Result.success(defaultPayload)
        if (originalBytes.size.toLong() <= avatarMaxBytes) return Result.success(defaultPayload)

        val compressed = compressImageToMaxBytes(uri = uri, maxBytes = avatarMaxBytes)
            ?: return Result.failure(
                Exception("No se pudo comprimir la imagen. Usa otra foto o reduce el tamaño del archivo.")
            )

        if (compressed.size.toLong() > avatarMaxBytes) {
            return Result.failure(
                Exception("La imagen supera 50 MB incluso después de comprimirla.")
            )
        }

        return Result.success(
            PreparedImagePayload(
                bytes = compressed,
                mimeType = "image/jpeg",
                extension = "jpg"
            )
        )
    }

    private fun extensionForMime(mimeType: String): String {
        return when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
    }

    private fun compressImageToMaxBytes(uri: Uri, maxBytes: Long): ByteArray? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDimension = 4096
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val baseBitmap = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        var workingBitmap = baseBitmap
        var lastCandidate: ByteArray? = null
        var quality = 92
        var attempt = 0

        try {
            while (attempt < 10) {
                val stream = ByteArrayOutputStream()
                workingBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                val candidate = stream.toByteArray()
                lastCandidate = candidate

                if (candidate.size.toLong() <= maxBytes) {
                    return candidate
                }

                if (quality > 55) {
                    quality -= 10
                } else {
                    val nextWidth = (workingBitmap.width * 0.85f).toInt().coerceAtLeast(720)
                    val nextHeight = (workingBitmap.height * 0.85f).toInt().coerceAtLeast(720)
                    if (nextWidth == workingBitmap.width && nextHeight == workingBitmap.height) {
                        break
                    }
                    val scaled = Bitmap.createScaledBitmap(workingBitmap, nextWidth, nextHeight, true)
                    if (workingBitmap !== baseBitmap) {
                        workingBitmap.recycle()
                    }
                    workingBitmap = scaled
                    quality = 82
                }
                attempt++
            }
        } finally {
            if (workingBitmap !== baseBitmap) {
                workingBitmap.recycle()
            }
            baseBitmap.recycle()
        }

        return lastCandidate
    }

    private fun inferImageMimeType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes.getOrNull(8) == 0x57.toByte() &&
            bytes.getOrNull(9) == 0x45.toByte() &&
            bytes.getOrNull(10) == 0x42.toByte() &&
            bytes.getOrNull(11) == 0x50.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    // ─────────────────────────────────────────────
    // GET ALL USERS (profiles)
    // ─────────────────────────────────────────────
    suspend fun getAllUsers(limit: Int = 100): Result<List<User>> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("select", "*")
                parameter("limit", limit)
                parameter("order", "created_at.desc")
            }

            if (response.status.isSuccess()) {
                val users = response.body<List<User>>()
                Result.success(users)
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(Exception("Error fetching users: ${response.status} - $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────
    // SEARCH USERS (name, username, numeric_id)
    // ─────────────────────────────────────────────
    suspend fun searchUsers(query: String): Result<List<User>> = withContext(Dispatchers.IO) {
        try {
            val sanitizedQuery = query.trim()
            if (sanitizedQuery.isEmpty()) return@withContext Result.success(emptyList())

            // 1. If it's a numeric ID, try exact match and also partial matches
            val numericId = sanitizedQuery.toLongOrNull()

            val response = supabaseConfig.client.postgrest["profiles"].select {
                filter {
                    if (numericId != null) {
                        or {
                            eq("numeric_id", numericId)
                            ilike("username", "%$sanitizedQuery%")
                            ilike("display_name", "%$sanitizedQuery%")
                            ilike("full_name", "%$sanitizedQuery%")
                        }
                    } else {
                        or {
                            ilike("username", "%$sanitizedQuery%")
                            ilike("display_name", "%$sanitizedQuery%")
                            ilike("full_name", "%$sanitizedQuery%")
                        }
                    }
                }
                limit(count = 20)
            }.decodeList<User>()

            android.util.Log.d("UserRepository", "✅ Search successful: ${response.size} users found")
            Result.success(response)
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "❌ Search failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun searchMentionCandidates(
        query: String,
        excludeUserId: String? = authService.getCurrentUserId(),
        limit: Int = 6
    ): Result<List<User>> = withContext(Dispatchers.IO) {
        searchUsers(query).fold(
            onSuccess = { users ->
                val cleanExclude = excludeUserId?.trim()?.lowercase()
                val blockedIds = cleanExclude?.let { currentUserId ->
                    getBlockedRelationshipUserIds(currentUserId).getOrElse { emptySet() }
                } ?: emptySet()
                val filtered = users
                    .asSequence()
                    .filter { isMentionableUser(it) }
                    .filterNot { user ->
                        cleanExclude != null && user.id.trim().lowercase() == cleanExclude
                    }
                    .filterNot { user ->
                        blockedIds.contains(user.id.trim().lowercase())
                    }
                    .distinctBy { it.id.trim().lowercase() }
                    .take(limit.coerceAtLeast(1))
                    .toList()
                Result.success(filtered)
            },
            onFailure = { Result.failure(it) }
        )
    }

    fun isMentionableUser(user: User): Boolean {
        val username = user.username?.trim().orEmpty()
        if (username.isBlank()) return false
        if (user.is_suspended || user.isSuspendedNow) return false

        val status = user.status?.trim()?.lowercase().orEmpty()
        if (status in NON_MENTIONABLE_STATUSES) return false

        return true
    }

    suspend fun getUsersByFollowerThreshold(threshold: Int): Result<List<User>> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("followers_count", "gte.$threshold")
                parameter("order", "followers_count.desc")
                parameter("limit", 50)
            }
            if (response.status.isSuccess()) {
                 val bodyStr = response.bodyAsText()
                 val users = Json { ignoreUnknownKeys = true }.decodeFromString<List<User>>(bodyStr)
                 Result.success(users)
            } else {
                 Result.failure(Exception("Error fetching admins: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────
    // SOCIAL GRAPH (Follow/Unfollow)
    // ─────────────────────────────────────────────

    @kotlinx.serialization.Serializable
    private data class FollowerResponse(
        @kotlinx.serialization.SerialName("profiles")
        val profile: User
    )

    @kotlinx.serialization.Serializable
    private data class FollowingResponse(
        @kotlinx.serialization.SerialName("profiles")
        val profile: User
    )

    suspend fun followUser(targetUserId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))
            val currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            if (currentUserId == targetUserId) {
                return Result.failure(Exception("No te puedes seguir a ti mismo"))
            }

            // 1. Insert into follows
            val followData = mapOf(
                "follower_id" to currentUserId,
                "following_id" to targetUserId
            )
            
            val response = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                setBody(followData)
            }

            if (!response.status.isSuccess()) {
                val err = try { response.body<String>() } catch (e: Exception) { "" }
                // Ignore key violation (already following)
                if (response.status == HttpStatusCode.Conflict || err.contains("duplicate key")) {
                    android.util.Log.w("UserRepository", "Already following $targetUserId (409). Treating as success.")
                    return Result.success(Unit)
                }
                android.util.Log.e("UserRepository", "Follow failed: ${response.status} - $err")
                return Result.failure(Exception("Failed to follow: $err"))
            }

            // 2. COUNTERS ARE NOW HANDLED BY DATABASE TRIGGERS
            // (on_follow_insert in database_social_triggers.sql)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unfollowUser(targetUserId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))
            val currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            // 1. Delete from follows
            val response = supabaseConfig.httpClient.delete(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("follower_id", "eq.$currentUserId")
                parameter("following_id", "eq.$targetUserId")
            }

            if (!response.status.isSuccess()) {
                return Result.failure(Exception("Failed to unfollow: ${response.status}"))
            }

            // 2. COUNTERS ARE NOW HANDLED BY DATABASE TRIGGERS
            // (on_follow_delete in database_social_triggers.sql)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkIsFollowing(targetUserId: String): Result<Boolean> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))

            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("follower_id", "eq.$currentUserId")
                parameter("following_id", "eq.$targetUserId")
                header("Range", "0-1")
            }

            if (response.status.isSuccess()) {
                val bodyStr = response.body<String>()
                // Robust checking using JSON parsing
                val isFollowing = try {
                    val json = Json { ignoreUnknownKeys = true }
                    val list = json.decodeFromString<List<Map<String, String>>>(bodyStr)
                    list.isNotEmpty()
                } catch (e: Exception) {
                     android.util.Log.w("UserRepository", "Error parsing checksIsFollowing: $bodyStr")
                     false
                }
                
                Result.success(isFollowing)
            } else {
                val err = response.body<String>()
                android.util.Log.e("UserRepository", "checkIsFollowing error: ${response.status} - $err")
                Result.failure(Exception("Check failed: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFollowers(userId: String): Result<List<User>> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            
            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("following_id", "eq.$userId")
                parameter("select", "profiles!follower_id(*)")
            }

             if (response.status.isSuccess()) {
                val rawBody = response.body<String>()
                val json = Json { ignoreUnknownKeys = true }
                // Parse List<FollowerResponse>
                val list = json.decodeFromString<List<FollowerResponse>>(rawBody)
                val users = list.map { it.profile }
                Result.success(users)
            } else {
                Result.failure(Exception("Fetch failed ${response.status}"))
            } 
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getFollowing(userId: String): Result<List<User>> {
         return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            
            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("follower_id", "eq.$userId")
                parameter("select", "profiles!following_id(*)")
            }

             if (response.status.isSuccess()) {
                val rawBody = response.body<String>()
                val json = Json { ignoreUnknownKeys = true }
                // Parse List<FollowingResponse>
                val list = json.decodeFromString<List<FollowingResponse>>(rawBody)
                val users = list.map { it.profile }
                Result.success(users)
            } else {
                Result.failure(Exception("Fetch failed ${response.status}"))
            } 
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun callRpc(funcName: String, params: JsonObject, token: String) {
        try {
            supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/rpc/$funcName"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                setBody(params)
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "RPC $funcName failed", e)
        }
    }

    // ─────────────────────────────────────────────
    // BLOCK/UNBLOCK USERS
    // ─────────────────────────────────────────────
    
    suspend fun blockUser(targetUserId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))
            val currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            val blockData = buildJsonObject {
                put("blocker_id", currentUserId)
                put("blocked_id", targetUserId)
            }
            
            val response = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/blocked_users"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                setBody(blockData)
            }

            if (response.status.isSuccess()) {
                invalidateBlockedMentionCache()
                Result.success(Unit)
            } else {
                val err = response.body<String>()
                if (err.contains("duplicate key")) {
                    invalidateBlockedMentionCache()
                    Result.success(Unit) // Already blocked
                } else {
                    Result.failure(Exception("Failed to block user"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unblockUser(targetUserId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))
            val currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            val response = supabaseConfig.httpClient.delete(
                "${supabaseConfig.supabaseUrl}/rest/v1/blocked_users"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("blocker_id", "eq.$currentUserId")
                parameter("blocked_id", "eq.$targetUserId")
            }

            if (response.status.isSuccess()) {
                invalidateBlockedMentionCache()
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to unblock user"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getBlockedRelationshipUserIds(currentUserId: String): Result<Set<String>> {
        return try {
            val now = System.currentTimeMillis()
            val normalizedCurrentUserId = currentUserId.trim().lowercase()
            val cachedUserId = blockedMentionCacheUserId

            if (
                cachedUserId == normalizedCurrentUserId &&
                now - blockedMentionCacheAtMs <= blockedMentionCacheTtlMs
            ) {
                return Result.success(blockedMentionCacheIds)
            }

            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/blocked_users"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("select", "blocker_id,blocked_id")
                parameter(
                    "or",
                    "(blocker_id.eq.$normalizedCurrentUserId,blocked_id.eq.$normalizedCurrentUserId)"
                )
                header("Range", "0-200")
            }

            if (!response.status.isSuccess()) {
                return Result.failure(Exception("Failed to fetch blocked relationships"))
            }

            val rows = response.body<List<JsonObject>>()
            val relatedIds = rows.mapNotNull { row ->
                val blockerId = row["blocker_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                val blockedId = row["blocked_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()

                when {
                    blockerId == normalizedCurrentUserId && !blockedId.isNullOrBlank() -> blockedId
                    blockedId == normalizedCurrentUserId && !blockerId.isNullOrBlank() -> blockerId
                    else -> null
                }
            }.toSet()

            blockedMentionCacheUserId = normalizedCurrentUserId
            blockedMentionCacheAtMs = now
            blockedMentionCacheIds = relatedIds

            Result.success(relatedIds)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun invalidateBlockedMentionCache() {
        blockedMentionCacheUserId = null
        blockedMentionCacheAtMs = 0L
        blockedMentionCacheIds = emptySet()
    }

    suspend fun getBlockRelationship(targetUserId: String): Result<BlockRelationship> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("No user logged in"))
            val cleanTargetId = targetUserId.trim().lowercase()
            if (cleanTargetId.isBlank()) return Result.success(BlockRelationship())

            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/blocked_users"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("select", "blocker_id,blocked_id")
                parameter(
                    "or",
                    "(and(blocker_id.eq.$currentUserId,blocked_id.eq.$cleanTargetId),and(blocker_id.eq.$cleanTargetId,blocked_id.eq.$currentUserId))"
                )
                header("Range", "0-10")
            }

            if (!response.status.isSuccess()) {
                return Result.failure(Exception("Failed to check block status"))
            }

            val rows = response.body<List<JsonObject>>()
            var blockedByMe = false
            var blockedMe = false
            rows.forEach { row ->
                val blockerId = row["blocker_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                val blockedId = row["blocked_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                if (blockerId == currentUserId && blockedId == cleanTargetId) blockedByMe = true
                if (blockerId == cleanTargetId && blockedId == currentUserId) blockedMe = true
            }

            Result.success(
                BlockRelationship(
                    blockedByMe = blockedByMe,
                    blockedMe = blockedMe
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeBlockRelationship(targetUserId: String): Flow<BlockRelationship> = callbackFlow {
        val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
        if (currentUserId.isNullOrBlank()) {
            close(IllegalStateException("No user logged in"))
            return@callbackFlow
        }
        val cleanTargetId = targetUserId.trim().lowercase()
        if (cleanTargetId.isBlank()) {
            trySend(BlockRelationship())
            close()
            return@callbackFlow
        }

        suspend fun refreshState() {
            val relation = getBlockRelationship(cleanTargetId).getOrNull() ?: return
            trySend(relation)
        }

        fun affectsPair(record: JsonObject): Boolean {
            return isBlockPairEvent(record, currentUserId, cleanTargetId)
        }

        val channel = supabaseConfig.client.realtime.channel("blocked-users-$currentUserId-$cleanTargetId")
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "blocked_users"
        }

        val collectorJob = launch {
            changes.collect { action ->
                val shouldRefresh = when (action) {
                    is PostgresAction.Insert -> affectsPair(action.record)
                    is PostgresAction.Update -> affectsPair(action.record)
                    is PostgresAction.Delete -> {
                        val oldData = action.oldRecord
                        oldData.isEmpty() || affectsPair(oldData)
                    }
                    else -> false
                }

                if (shouldRefresh) {
                    invalidateBlockedMentionCache()
                    refreshState()
                }
            }
        }

        val subscribeJob = launch {
            channel.subscribe()
            refreshState()
        }

        awaitClose {
            collectorJob.cancel()
            subscribeJob.cancel()
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun isBlockPairEvent(
        record: JsonObject,
        currentUserId: String,
        targetUserId: String
    ): Boolean {
        val blockerId = record["blocker_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val blockedId = record["blocked_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        if (blockerId.isNullOrBlank() || blockedId.isNullOrBlank()) return false
        return (blockerId == currentUserId && blockedId == targetUserId) ||
            (blockerId == targetUserId && blockedId == currentUserId)
    }

    suspend fun isBlocked(targetUserId: String): Result<Boolean> {
        return getBlockRelationship(targetUserId).map { it.isBlocked }
    }

    suspend fun getVerifiedUsers(): Result<List<User>> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("is_verified", "eq.true")
                parameter("select", "*")
            }

            if (response.status.isSuccess()) {
                val users = response.body<List<User>>()
                Result.success(users)
            } else {
                Result.failure(Exception("Failed to fetch verified users"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────
    // VOICE ACCESS CONTROL
    // ─────────────────────────────────────────────

    suspend fun getVoiceAccessRequestStatus(userId: String): Result<String> {
        return try {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            
            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/voice_access_requests"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("user_id", "eq.$userId")
                parameter("select", "status")
            }

            android.util.Log.d("UserRepository", "Fetching request status for $userId. Response: ${response.status}")

            if (response.status.isSuccess()) {
                val bodyStr = response.bodyAsText()
                val json = Json { ignoreUnknownKeys = true }
                val list = json.decodeFromString<List<JsonObject>>(bodyStr)
                if (list.isNotEmpty()) {
                    val statusElement = list[0]["status"]
                    val status = if (statusElement is kotlinx.serialization.json.JsonPrimitive) {
                        statusElement.content
                    } else {
                        "none"
                    }
                    Result.success(status)
                } else {
                    Result.success("none")
                }
            } else {
                Result.failure(Exception("Failed to fetch status: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestVoiceAccess(userId: String): Result<Unit> {
        return try {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            
            val payload = mapOf(
                "user_id" to userId,
                "status" to "pending"
            )

            val response = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/voice_access_requests"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                header("Prefer", "resolution=merge-duplicates")
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                android.util.Log.d("UserRepository", "Voice access request successful: ${response.status}")
                Result.success(Unit)
            } else if (response.status == io.ktor.http.HttpStatusCode.Conflict) {
                android.util.Log.d("UserRepository", "Voice access request already exists (409 Conflict)")
                Result.success(Unit) // Treat conflict as success since record exists
            } else {
                val err = response.bodyAsText()
                android.util.Log.e("UserRepository", "Voice access request failed: ${response.status} - $err")
                Result.failure(Exception("Error servidor (${response.status.value})"))
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Exception in requestVoiceAccess", e)
            Result.failure(e)
        }
    }

    suspend fun getAdmins(): Result<List<User>> {
        return try {
            val token = authService.getAccessToken()
            
            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                if (token != null) header("Authorization", "Bearer $token")
                parameter("is_admin", "eq.true")
                parameter("select", "*")
            }
            
            if (response.status.isSuccess()) {
                 val bodyStr = response.bodyAsText()
                 val users = Json { ignoreUnknownKeys = true }.decodeFromString<List<User>>(bodyStr)
                 Result.success(users)
            } else {
                 Result.failure(Exception("Error fetching admins: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createVoiceAccessReport(userId: String, userName: String): Result<Unit> {
        return try {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            
            val payload = mapOf(
                "reporter_id" to userId,
                "reporter_name" to userName,
                "reported_user_id" to userId,
                "reported_user_name" to userName,
                "reason" to "Solicitud de Acceso a Voz",
                "description" to "El usuario solicita acceso a la sección de salas de voz en desarrollo.",
                "status" to "pendiente"
            )
            
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/reports") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(payload)
            }
            
            if (response.status.isSuccess()) {
                android.util.Log.d("UserRepository", "Voice access report created")
                Result.success(Unit)
            } else {
                val err = response.bodyAsText()
                android.util.Log.e("UserRepository", "Report creation failed: ${response.status} - $err")
                Result.failure(Exception("Error creating report: ${response.status}"))
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Exception in createVoiceAccessReport", e)
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────
    // ONLINE PRESENCE
    // ─────────────────────────────────────────────
    
    /**
     * Actualizar el estado online del usuario actual
     */
    suspend fun setOnlineStatus(userId: String, isOnline: Boolean): Result<Unit> {
        return try {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            
            android.util.Log.d("UserRepository", "Setting online status for $userId to $isOnline")
            
            val response = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/rpc/update_user_online_status"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                setBody(buildJsonObject {
                    put("user_id", userId)
                    put("online_status", isOnline)
                })
            }
            
            if (response.status.isSuccess()) {
                android.util.Log.d("UserRepository", "Online status updated successfully")
                Result.success(Unit)
            } else {
                val err = response.bodyAsText()
                android.util.Log.e("UserRepository", "Failed to update online status: ${response.status} - $err")
                Result.failure(Exception("Failed to update status"))
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Exception updating online status", e)
            Result.failure(e)
        }
    }
    
    /**
     * Observar cambios en el estado de presencia de un usuario
     * Retorna un Flow que emite true cuando está online, false cuando está offline
     * Usa polling cada 30 segundos para verificar el estado
     */
    fun observeUserPresence(userId: String): kotlinx.coroutines.flow.Flow<User> = kotlinx.coroutines.flow.flow {
        while (true) {
            try {
                // Fetch fresh user data including last_seen
                val result = getUserById(userId)
                val user = result.getOrNull()
                if (user != null) {
                    emit(user)
                }
            } catch (e: Exception) {
                android.util.Log.e("UserRepository", "Error checking user presence", e)
            }
            kotlinx.coroutines.delay(10000) // Polling interval: 10 seconds
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    private companion object {
        val NON_MENTIONABLE_STATUSES = setOf(
            "deleted",
            "deactivated",
            "disabled",
            "removed",
            "banned",
            "suspended"
        )
    }
}
