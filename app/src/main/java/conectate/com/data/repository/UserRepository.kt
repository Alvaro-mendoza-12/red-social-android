package conectate.com.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import conectate.com.core.media.ProfileMediaMutation
import conectate.com.core.media.ProfileMediaSync
import conectate.com.core.testing.TestRuntimeOverrides
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.LocalFallbackProfileResponse
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.User
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.repository.user.UserLocalStateStore
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.util.canonicalizeMediaContentType
import conectate.com.util.inferMimeTypeFromBytes
import conectate.com.util.preferredExtensionForMime
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.filter.*
import io.github.jan.supabase.realtime.*
import io.github.jan.supabase.gotrue.auth
import javax.inject.Inject
import javax.inject.Singleton

data class BlockRelationship(
    val blockedByMe: Boolean = false,
    val blockedMe: Boolean = false
) {
    val isBlocked: Boolean get() = blockedByMe || blockedMe
}

data class ProfileAvatarUpload(
    val url: String,
    val mediaType: String,
    val durationMs: Long? = null
)

@Singleton
class UserRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val storageService: StorageService,
    private val localFallbackApiClient: LocalFallbackApiClient,
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
    private val localStateStore = UserLocalStateStore(context)

    private val _profileUpdates = kotlinx.coroutines.flow.MutableSharedFlow<User>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val profileUpdates: kotlinx.coroutines.flow.SharedFlow<User> = _profileUpdates.asSharedFlow()

    private fun shouldUseUbuntuBackend(): Boolean =
        localFallbackApiClient.isConfigured &&
            (supabaseConfig.isLocalFallbackPrimary || authService.isLocalFallbackSession())

    private fun normalizePrivacyUserId(userId: String): String = localStateStore.normalizeUserId(userId)

    private fun sanitizeProfileText(value: String?): String? {
        return value
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun slugifyUsername(value: String?): String {
        val raw = sanitizeProfileText(value) ?: return ""
        var normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

        return normalized
            .lowercase()
            .replace(Regex("[^a-z0-9._]"), "_")
            .replace(Regex("[._]{2,}"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.')
            .take(24)
    }

    private fun buildDeterministicUsername(userId: String): String {
        return "usuario_${normalizePrivacyUserId(userId).take(8)}"
    }

    private fun JsonObject.stringValue(vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            runCatching {
                this[key]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            }.getOrNull()
        }
    }

    private fun JsonObject.intValue(vararg keys: String): Int? {
        return keys.firstNotNullOfOrNull { key ->
            runCatching {
                this[key]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.toDoubleOrNull()
                    ?.toInt()
            }.getOrNull()
        }
    }

    private fun JsonObject.booleanValue(vararg keys: String): Boolean? {
        return keys.firstNotNullOfOrNull { key ->
            runCatching {
                when (this[key]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
            }.getOrNull()
        }
    }

    private fun JsonObject.stringListValue(vararg keys: String): List<String> {
        return keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonArray)?.mapNotNull { item ->
                runCatching { item.jsonPrimitive.contentOrNull?.trim() }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
            }
        }.orEmpty()
    }

    private fun JsonObject.stringMapValue(vararg keys: String): Map<String, String> {
        return keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonObject)?.mapNotNull { (entryKey, item) ->
                val value = runCatching { item.jsonPrimitive.contentOrNull?.trim() }.getOrNull()
                if (entryKey.isBlank() || value.isNullOrBlank()) null else entryKey to value
            }?.toMap()
        }.orEmpty()
    }

    private fun sanitizeLocalFallbackAssetUrl(value: String?): String? {
        val clean = sanitizeProfileText(value) ?: return null
        return UbuntuStorageUrl.rewrite(clean)
    }

    private fun mapLocalFallbackProfile(
        response: LocalFallbackProfileResponse,
        fallbackId: String,
        fallbackEmail: String?
    ): User? {
        val profile = response.profile ?: return null
        val normalizedId = normalizePrivacyUserId(
            profile.stringValue("supabase_user_id", "supabaseUserId", "id", "user_id", "profile_id")
                ?: fallbackId
        )
        val email = sanitizeProfileText(profile.stringValue("email"))
            ?: fallbackEmail?.trim()?.lowercase()
            ?: authService.getCurrentUserEmail()?.trim()?.lowercase()
            ?: ""
        val displayName = sanitizeProfileText(
            profile.stringValue("display_name", "displayName", "full_name", "name", "username")
        ) ?: email.substringBefore("@").ifBlank { "Usuario" }

        return applyLocalUserOverrides(
            User(
                id = normalizedId,
                email = email,
                username = sanitizeProfileText(profile.stringValue("username"))
                    ?: slugifyUsername(displayName).ifBlank { buildDeterministicUsername(normalizedId) },
                full_name = sanitizeProfileText(profile.stringValue("full_name", "fullName", "name")) ?: displayName,
                display_name = displayName,
                phone = sanitizeProfileText(profile.stringValue("phone", "phone_number")),
                bio = sanitizeProfileText(profile.stringValue("bio", "about")),
                avatar_url = sanitizeLocalFallbackAssetUrl(
                    profile.stringValue("avatar_url", "avatarUrl", "photo_url", "profile_image", "picture", "image")
                ),
                avatar_media_type = sanitizeProfileText(profile.stringValue("avatar_media_type", "avatarMediaType")),
                avatar_video_muted = profile.booleanValue("avatar_video_muted", "avatarVideoMuted") ?: true,
                avatar_duration_ms = profile.intValue("avatar_duration_ms", "avatarDurationMs")?.toLong(),
                cover_url = sanitizeLocalFallbackAssetUrl(profile.stringValue("cover_url", "coverUrl", "cover_image")),
                profile_theme = sanitizeProfileText(profile.stringValue("profile_theme", "profileTheme")) ?: "conectate",
                profile_effect = sanitizeProfileText(profile.stringValue("profile_effect", "profileEffect")) ?: "none",
                profile_welcome = sanitizeProfileText(profile.stringValue("profile_welcome", "profileWelcome")),
                country = sanitizeProfileText(profile.stringValue("country")),
                city = sanitizeProfileText(profile.stringValue("city")),
                gender = sanitizeProfileText(profile.stringValue("gender"))
                    ?.takeIf { it in setOf("man", "woman", "unspecified") }
                    ?: "unspecified",
                birth_date = sanitizeProfileText(profile.stringValue("birth_date", "birthDate")),
                status = sanitizeProfileText(profile.stringValue("status")),
                interests = profile.stringListValue("interests"),
                hobbies = profile.stringListValue("hobbies"),
                linked_games = profile.stringListValue("linked_games", "linkedGames"),
                game_logo_urls = profile.stringMapValue("game_logo_urls", "gameLogoUrls"),
                favorite_music_url = sanitizeLocalFallbackAssetUrl(
                    profile.stringValue("favorite_music_url", "favoriteMusicUrl")
                ),
                favorite_book = sanitizeProfileText(profile.stringValue("favorite_book", "favoriteBook")),
                favorite_sport = sanitizeProfileText(profile.stringValue("favorite_sport", "favoriteSport")),
                career = sanitizeProfileText(profile.stringValue("career", "profession")),
                availability_schedule = sanitizeProfileText(
                    profile.stringValue("availability_schedule", "availabilitySchedule")
                ),
                profile_section_order = profile.stringListValue("profile_section_order", "profileSectionOrder"),
                profile_section_visibility = profile.stringMapValue(
                    "profile_section_visibility",
                    "profileSectionVisibility"
                ),
                followers_count = profile.intValue("followers_count", "follower_count", "followers") ?: response.stats.followers,
                following_count = profile.intValue("following_count", "following") ?: response.stats.following,
                post_count = profile.intValue("post_count", "posts_count", "posts") ?: response.stats.posts,
                is_private = profile.booleanValue("is_private", "private") ?: false,
                favorites_public = profile.booleanValue("favorites_public", "favoritesPublic") ?: false,
                break_mode = sanitizeProfileText(profile.stringValue("break_mode", "breakMode")) ?: "off",
                profile_visibility = sanitizeProfileText(
                    profile.stringValue("profile_visibility", "profileVisibility")
                ) ?: "everyone",
                last_seen_visibility = sanitizeProfileText(
                    profile.stringValue("last_seen_visibility", "lastSeenVisibility")
                ) ?: "everyone",
                online_status_visibility = sanitizeProfileText(
                    profile.stringValue("online_status_visibility", "onlineStatusVisibility")
                ) ?: "everyone",
                last_name_change = sanitizeProfileText(profile.stringValue("last_name_change", "lastNameChange")),
                is_admin = profile.booleanValue("is_admin", "admin") ?: false,
                voice_access_granted = profile.booleanValue("voice_access_granted") ?: false,
                is_verified = profile.booleanValue("is_verified", "verified") ?: false,
                privacy_policy_accepted = profile.booleanValue("privacy_policy_accepted") ?: true,
                privacy_policy_accepted_at = sanitizeProfileText(profile.stringValue("privacy_policy_accepted_at")),
                created_at = sanitizeProfileText(profile.stringValue("created_at")) ?: "",
                updated_at = sanitizeProfileText(profile.stringValue("updated_at")) ?: ""
            )
        )
    }

    private suspend fun loadCurrentLocalFallbackProfile(): Result<User?> {
        val token = authService.getAccessTokenAwait()
            ?: return Result.failure(Exception("No access token"))
        val fallbackId = authService.getCurrentUserId()
            ?: return Result.failure(Exception("No user logged in"))
        val fallbackEmail = authService.getCurrentUserEmail()

        return localFallbackApiClient.loadCurrentProfile(token)
            .map { response ->
                val user = mapLocalFallbackProfile(response, fallbackId, fallbackEmail)
                if (user != null) {
                    userDao.insertUser(conectate.com.data.local.entity.UserEntity.fromDomain(user))
                    saveProfileLocally(user)
                }
                user
            }
    }

    private suspend fun isUsernameAvailable(
        token: String,
        username: String,
        currentUserId: String
    ): Boolean {
        if (username.isBlank()) return false

        return try {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("username", "eq.$username")
                parameter("select", "id")
                parameter("limit", 1)
            }

            if (!response.status.isSuccess()) {
                return false
            }

            var rows = response.body<List<JsonObject>>()
            var existingId = rows.firstOrNull()
                ?.get("id")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.lowercase()

            existingId == null || existingId == currentUserId
        } catch (e: Exception) {
            android.util.Log.w("UserRepository", "Username availability check failed for $username", e)
            false
        }
    }

    private suspend fun resolveUsernameForProfile(
        userId: String,
        preferredUsername: String?,
        preferredDisplayName: String?,
        email: String
    ): String {
        var normalizedUserId = normalizePrivacyUserId(userId)
        val emailLocalPart = email.substringBefore("@").takeIf { it.isNotBlank() && it != email }
        val baseCandidate = listOf(
            preferredUsername,
            preferredDisplayName,
            emailLocalPart,
            "usuario"
        ).map(::slugifyUsername).firstOrNull { it.isNotBlank() }
            ?: buildDeterministicUsername(normalizedUserId)

        val candidateWithSuffix = "${baseCandidate.take(17)}_${normalizedUserId.take(6)}"
            .trim('_')
            .take(24)
            .ifBlank { buildDeterministicUsername(normalizedUserId) }

        val fallbackCandidate = buildDeterministicUsername(normalizedUserId)
        var candidates = linkedSetOf(baseCandidate, candidateWithSuffix, fallbackCandidate)
        var token = authService.getAccessTokenAwait()

        if (token.isNullOrBlank()) {
            return candidates.first()
        }

        candidates.forEach { candidate ->
            if (isUsernameAvailable(token, candidate, normalizedUserId)) {
                return candidate
            }
        }

        return fallbackCandidate
    }

    private suspend fun prepareProfileForPersistence(user: User): User {
        val normalizedId = normalizePrivacyUserId(user.id)
        var sessionSeed = authService.getCurrentUserProfileSeed()
        val normalizedEmail = sanitizeProfileText(user.email)?.lowercase()
            ?: sessionSeed?.email?.trim()?.lowercase()
            ?: authService.getCurrentUserEmail()?.trim()?.lowercase()
            ?: ""
        var displayName = sanitizeProfileText(user.display_name)
            ?: sanitizeProfileText(user.full_name)
            ?: sanitizeProfileText(sessionSeed?.displayName)
            ?: sanitizeProfileText(sessionSeed?.fullName)
            ?: normalizedEmail.substringBefore("@").trim().ifBlank { "Usuario" }
        var fullName = sanitizeProfileText(user.full_name)
            ?: sanitizeProfileText(user.display_name)
            ?: sanitizeProfileText(sessionSeed?.fullName)
            ?: displayName
        val resolvedUsername = resolveUsernameForProfile(
            userId = normalizedId,
            preferredUsername = user.username,
            preferredDisplayName = displayName,
            email = normalizedEmail
        )

        return user.copy(
            id = normalizedId,
            email = normalizedEmail,
            username = resolvedUsername,
            full_name = fullName,
            display_name = sanitizeProfileText(user.display_name) ?: fullName,
            avatar_url = sanitizeProfileText(user.avatar_url) ?: sanitizeProfileText(sessionSeed?.avatarUrl),
            country = sanitizeProfileText(user.country) ?: sanitizeProfileText(sessionSeed?.country),
            city = sanitizeProfileText(user.city),
            bio = sanitizeProfileText(user.bio)
        )
    }

    private fun buildProfilePayload(
        user: User,
        privacyPolicyAccepted: Boolean,
        acceptedAt: String?
    ): JsonObject {
        return buildJsonObject {
            put("id", JsonPrimitive(user.id))
            put("email", JsonPrimitive(user.email))
            user.username?.let { put("username", JsonPrimitive(it)) }
            user.full_name?.let { put("full_name", JsonPrimitive(it)) }
            user.display_name?.let { put("display_name", JsonPrimitive(it)) }
            user.country?.let { put("country", JsonPrimitive(it)) }
            user.city?.let { put("city", JsonPrimitive(it)) }
            put("gender", JsonPrimitive(user.gender))
            user.birth_date?.let { put("birth_date", JsonPrimitive(it)) }
            user.avatar_url?.let { put("avatar_url", JsonPrimitive(it)) }
            user.bio?.let { put("bio", JsonPrimitive(it)) }
            if (privacyPolicyAccepted) {
                put("privacy_policy_accepted", JsonPrimitive(true))
                if (acceptedAt != null) {
                    put("privacy_policy_accepted_at", JsonPrimitive(acceptedAt))
                }
            }
        }
    }

    private suspend fun submitProfileUpsert(
        token: String,
        body: JsonObject
    ): HttpResponse {
        return supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
            header("apikey", supabaseConfig.supabaseAnonKey)
            header("Authorization", "Bearer $token")
            header("Content-Type", "application/json")
            header("Prefer", "resolution=merge-duplicates,return=representation")
            parameter("on_conflict", "id")
            setBody(body)
        }
    }

    private fun isMissingPrivacyAcceptedColumn(errorBody: String): Boolean {
        return errorBody.contains("privacy_policy_accepted", ignoreCase = true) &&
            errorBody.contains("does not exist", ignoreCase = true)
    }

    private fun isDuplicateUsernameError(errorBody: String): Boolean {
        return errorBody.contains("username", ignoreCase = true) &&
            (
                errorBody.contains("duplicate key", ignoreCase = true) ||
                    errorBody.contains("already exists", ignoreCase = true) ||
                    errorBody.contains("23505")
                )
    }

    internal fun buildProfileUpdateFields(
        request: UpdateProfileRequest,
        lastNameChange: String?
    ): LinkedHashMap<String, JsonElement> {
        return linkedMapOf<String, JsonElement>().apply {
            request.display_name?.let {
                put("display_name", JsonPrimitive(it))
                put("full_name", JsonPrimitive(it))
            }
            request.bio?.let { put("bio", JsonPrimitive(it)) }
            request.country?.let { put("country", JsonPrimitive(it)) }
            request.city?.let { put("city", JsonPrimitive(it)) }
            request.gender?.let { put("gender", JsonPrimitive(it)) }
            request.birth_date?.let { put("birth_date", JsonPrimitive(it)) }
            request.status?.let { put("status", JsonPrimitive(it)) }
            request.interests?.let { values ->
                put("interests", JsonArray(values.map { JsonPrimitive(it) }))
            }
            request.hobbies?.let { values ->
                put("hobbies", JsonArray(values.map { JsonPrimitive(it) }))
            }
            request.linked_games?.let { values ->
                put("linked_games", JsonArray(values.map { JsonPrimitive(it) }))
            }
            request.game_logo_urls?.let { logos ->
                put("game_logo_urls", JsonObject(logos.mapValues { JsonPrimitive(it.value) }))
            }
            request.favorite_music_url?.let { put("favorite_music_url", JsonPrimitive(it)) }
            request.favorite_book?.let { put("favorite_book", JsonPrimitive(it)) }
            request.favorite_sport?.let { put("favorite_sport", JsonPrimitive(it)) }
            request.career?.let { put("career", JsonPrimitive(it)) }
            request.availability_schedule?.let { put("availability_schedule", JsonPrimitive(it)) }
            request.profile_section_order?.let { values ->
                put("profile_section_order", JsonArray(values.map { JsonPrimitive(it) }))
            }
            request.profile_section_visibility?.let { values ->
                put("profile_section_visibility", JsonObject(values.mapValues { JsonPrimitive(it.value) }))
            }
            request.avatar_url?.let { put("avatar_url", JsonPrimitive(it)) }
            request.avatar_media_type?.let { put("avatar_media_type", JsonPrimitive(it)) }
            request.avatar_video_muted?.let { put("avatar_video_muted", JsonPrimitive(it)) }
            request.avatar_duration_ms?.let {
                put("avatar_duration_ms", JsonPrimitive(it.coerceIn(0L, PROFILE_AVATAR_MAX_DURATION_MS)))
            }
            request.cover_url?.let { put("cover_url", JsonPrimitive(it)) }
            if (request.remove_avatar) {
                put("avatar_url", JsonNull)
                put("avatar_media_type", JsonNull)
                put("avatar_duration_ms", JsonNull)
            }
            if (request.remove_cover) put("cover_url", JsonNull)
            request.profile_theme?.let { put("profile_theme", JsonPrimitive(it)) }
            request.profile_effect?.let { put("profile_effect", JsonPrimitive(it)) }
            request.profile_welcome?.let { put("profile_welcome", JsonPrimitive(it)) }
            lastNameChange?.let { put("last_name_change", JsonPrimitive(it)) }
            request.is_private?.let { put("is_private", JsonPrimitive(it)) }
            request.favorites_public?.let { put("favorites_public", JsonPrimitive(it)) }
            request.break_mode?.let { put("break_mode", JsonPrimitive(it)) }
            request.profile_visibility?.let { put("profile_visibility", JsonPrimitive(it)) }
            request.last_seen_visibility?.let { put("last_seen_visibility", JsonPrimitive(it)) }
            request.online_status_visibility?.let { put("online_status_visibility", JsonPrimitive(it)) }
            request.balance?.let { put("balance", JsonPrimitive(it)) }
        }
    }

    private fun profileUpdateMismatch(request: UpdateProfileRequest, user: User): String? {
        fun sameText(expected: String, actual: String?): Boolean =
            expected.trim() == actual.orEmpty().trim()

        fun sameList(expected: List<String>, actual: List<String>): Boolean =
            expected.map(String::trim) == actual.map(String::trim)

        fun sameUrl(expected: String, actual: String?): Boolean =
            ProfileMediaSync.sameReference(expected, actual) || sameText(expected, actual)

        if (request.display_name?.let { !sameText(it, user.display_name ?: user.full_name) } == true) return "display_name"
        if (request.bio?.let { !sameText(it, user.bio) } == true) return "bio"
        if (request.country?.let { !sameText(it, user.country) } == true) return "country"
        if (request.city?.let { !sameText(it, user.city) } == true) return "city"
        if (request.gender?.let { !sameText(it, user.gender) } == true) return "gender"
        if (request.birth_date?.let { !sameText(it, user.birth_date) } == true) return "birth_date"
        if (request.status?.let { !sameText(it, user.status) } == true) return "status"
        if (request.interests?.let { !sameList(it, user.interests) } == true) return "interests"
        if (request.hobbies?.let { !sameList(it, user.hobbies) } == true) return "hobbies"
        if (request.linked_games?.let { !sameList(it, user.linked_games) } == true) return "linked_games"
        if (request.game_logo_urls?.let { it != user.game_logo_urls } == true) return "game_logo_urls"
        if (request.favorite_music_url?.let { !sameUrl(it, user.favorite_music_url) } == true) return "favorite_music_url"
        if (request.favorite_book?.let { !sameText(it, user.favorite_book) } == true) return "favorite_book"
        if (request.favorite_sport?.let { !sameText(it, user.favorite_sport) } == true) return "favorite_sport"
        if (request.career?.let { !sameText(it, user.career) } == true) return "career"
        if (request.availability_schedule?.let { !sameText(it, user.availability_schedule) } == true) {
            return "availability_schedule"
        }
        if (request.profile_section_order?.let { !sameList(it, user.profile_section_order) } == true) {
            return "profile_section_order"
        }
        if (request.profile_section_visibility?.let { it != user.profile_section_visibility } == true) {
            return "profile_section_visibility"
        }
        ProfileMediaSync.confirmationFailure(
            mutation = ProfileMediaMutation(
                avatarUrl = request.avatar_url,
                coverUrl = request.cover_url,
                removeAvatar = request.remove_avatar,
                removeCover = request.remove_cover
            ),
            actualAvatarUrl = user.avatar_url,
            actualCoverUrl = user.cover_url
        )?.let { return it.field.name.lowercase() + "_url" }
        if (request.avatar_media_type?.let { !sameText(it, user.avatar_media_type) } == true) return "avatar_media_type"
        if (request.avatar_video_muted != null && request.avatar_video_muted != user.avatar_video_muted) {
            return "avatar_video_muted"
        }
        if (request.avatar_duration_ms != null && request.avatar_duration_ms != user.avatar_duration_ms) {
            return "avatar_duration_ms"
        }
        if (request.profile_theme?.let { !sameText(it, user.profile_theme) } == true) return "profile_theme"
        if (request.profile_effect?.let { !sameText(it, user.profile_effect) } == true) return "profile_effect"
        if (request.profile_welcome?.let { !sameText(it, user.profile_welcome) } == true) return "profile_welcome"
        if (request.is_private != null && request.is_private != user.is_private) return "is_private"
        if (request.favorites_public != null && request.favorites_public != user.favorites_public) return "favorites_public"
        if (request.break_mode?.let { !sameText(it, user.break_mode) } == true) return "break_mode"
        if (request.profile_visibility?.let { !sameText(it, user.profile_visibility) } == true) return "profile_visibility"
        if (request.last_seen_visibility?.let { !sameText(it, user.last_seen_visibility) } == true) {
            return "last_seen_visibility"
        }
        if (request.online_status_visibility?.let { !sameText(it, user.online_status_visibility) } == true) {
            return "online_status_visibility"
        }
        return null
    }

    private fun extractMissingProfilesColumn(errorBody: String): String? {
        val patterns = listOf(
            Regex("Could not find the '([a-zA-Z0-9_]+)' column of 'profiles'", RegexOption.IGNORE_CASE),
            Regex("column\\s+['\"]?profiles\\.([a-zA-Z0-9_]+)['\"]?\\s+does not exist", RegexOption.IGNORE_CASE),
            Regex("column\\s+['\"]?([a-zA-Z0-9_]+)['\"]?\\s+does not exist", RegexOption.IGNORE_CASE)
        )

        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(errorBody)?.groupValues?.getOrNull(1)
        }?.trim()?.lowercase()
    }

    private suspend fun fetchRemoteUserById(
        userId: String,
        token: String,
        currentUserId: String?
    ): Result<User?> {
        return try {
            suspend fun requestProfile(accessToken: String): Pair<HttpResponse, String> {
                var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Authorization", "Bearer $accessToken")
                    parameter("id", "eq.$userId")
                    parameter("select", "*")
                    parameter("limit", 1)
                }
                return response to response.bodyAsText()
            }

            var (response, rawBody) = requestProfile(token)

            if (isJwtExpiredError(response.status, rawBody)) {
                var refreshedToken = refreshAccessToken()
                if (!refreshedToken.isNullOrBlank()) {
                    var retry = requestProfile(refreshedToken)
                    response = retry.first
                    rawBody = retry.second
                }
            }

            if (!response.status.isSuccess()) {
                return Result.failure(Exception(profileLoadErrorMessage(response.status, rawBody)))
            }

            var users = supabaseConfig.json.decodeFromString<List<User>>(rawBody)
            var user = users.firstOrNull()?.let(::applyLocalUserOverrides)
            android.util.Log.d("UserRepository", "Fetched user $userId: ${user?.displayName}")

            if (user != null) {
                userDao.insertUser(conectate.com.data.local.entity.UserEntity.fromDomain(user))
                if (userId == currentUserId) {
                    saveProfileLocally(user)
                }
            } else {
                android.util.Log.w("UserRepository", "Network profile lookup returned empty for $userId")
            }

            Result.success(user)
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Network fetch failed for $userId", e)
            Result.failure(e)
        }
    }

    suspend fun ensureCurrentProfile(userHint: User? = null): Result<User> {
        TestRuntimeOverrides.currentUser(context)?.let { fakeUser ->
            return Result.success(fakeUser)
        }

        if (shouldUseUbuntuBackend()) {
            return loadCurrentLocalFallbackProfile()
                .mapCatching { user ->
                    user ?: throw IllegalStateException("No se pudo cargar el perfil desde Ubuntu")
                }
        }

        var currentUserId = authService.getCurrentUserId()
            ?.let(::normalizePrivacyUserId)
            ?: return Result.failure(Exception("No user logged in"))
        var token = authService.getAccessTokenAwait()
            ?: return Result.failure(Exception("No access token"))

        fetchRemoteUserById(currentUserId, token, currentUserId).getOrNull()?.let { existingUser ->
            return Result.success(existingUser)
        }

        var sessionSeed = authService.getCurrentUserProfileSeed()
        val localCurrent = getLocalProfileRaw()
            ?.takeIf { normalizePrivacyUserId(it.id) == currentUserId }
        var cachedEntity = runCatching { userDao.getUser(currentUserId)?.toDomain() }.getOrNull()
        val resolvedEmail = sanitizeProfileText(userHint?.email)?.lowercase()
            ?: sanitizeProfileText(sessionSeed?.email)?.lowercase()
            ?: sanitizeProfileText(localCurrent?.email)?.lowercase()
            ?: authService.getCurrentUserEmail()?.trim()?.lowercase()
            ?: return Result.failure(Exception("No se pudo reconstruir el correo del perfil"))
        val resolvedDisplayName = sanitizeProfileText(userHint?.display_name)
            ?: sanitizeProfileText(userHint?.full_name)
            ?: sanitizeProfileText(sessionSeed?.displayName)
            ?: sanitizeProfileText(sessionSeed?.fullName)
            ?: sanitizeProfileText(localCurrent?.display_name)
            ?: sanitizeProfileText(localCurrent?.full_name)
            ?: sanitizeProfileText(cachedEntity?.full_name)
            ?: resolvedEmail.substringBefore("@").trim().ifBlank { "Usuario" }
        val privacyAccepted = userHint?.privacy_policy_accepted == true ||
            localCurrent?.privacy_policy_accepted == true ||
            sessionSeed?.privacyPolicyAccepted == true ||
            localStateStore.getLocalPrivacyAccepted(currentUserId)
        val seedUser = User(
            id = currentUserId,
            email = resolvedEmail,
            username = sanitizeProfileText(userHint?.username)
                ?: sanitizeProfileText(localCurrent?.username)
                ?: sanitizeProfileText(cachedEntity?.username),
            full_name = sanitizeProfileText(userHint?.full_name)
                ?: sanitizeProfileText(localCurrent?.full_name)
                ?: sanitizeProfileText(cachedEntity?.full_name)
                ?: resolvedDisplayName,
            display_name = sanitizeProfileText(userHint?.display_name)
                ?: sanitizeProfileText(localCurrent?.display_name)
                ?: sanitizeProfileText(sessionSeed?.displayName)
                ?: resolvedDisplayName,
            avatar_url = sanitizeProfileText(userHint?.avatar_url)
                ?: sanitizeProfileText(localCurrent?.avatar_url)
                ?: sanitizeProfileText(cachedEntity?.avatar_url)
                ?: sanitizeProfileText(sessionSeed?.avatarUrl),
            country = sanitizeProfileText(userHint?.country)
                ?: sanitizeProfileText(localCurrent?.country)
                ?: sanitizeProfileText(sessionSeed?.country),
            city = sanitizeProfileText(userHint?.city)
                ?: sanitizeProfileText(localCurrent?.city),
            bio = sanitizeProfileText(userHint?.bio)
                ?: sanitizeProfileText(localCurrent?.bio)
                ?: sanitizeProfileText(cachedEntity?.bio),
            privacy_policy_accepted = privacyAccepted,
            privacy_policy_accepted_at = sanitizeProfileText(userHint?.privacy_policy_accepted_at)
                ?: sanitizeProfileText(localCurrent?.privacy_policy_accepted_at)
                ?: sanitizeProfileText(sessionSeed?.privacyPolicyAcceptedAt)
                ?: localStateStore.getLocalPrivacyAcceptedAt(currentUserId)
        )

        createProfile(seedUser, privacyPolicyAccepted = privacyAccepted)
            .getOrElse { return Result.failure(it) }

        val repairedUser = fetchRemoteUserById(currentUserId, token, currentUserId).getOrNull()
            ?: getLocalProfile()?.takeIf { normalizePrivacyUserId(it.id) == currentUserId }
            ?: applyLocalUserOverrides(seedUser)

        return Result.success(repairedUser)
    }

    // ─────────────────────────────────────────────
    // GET USER BY ID
    // ─────────────────────────────────────────────
    suspend fun getUserById(userId: String, forceNetwork: Boolean = true): Result<User?> {
        TestRuntimeOverrides.currentUser(context)
            ?.takeIf { normalizePrivacyUserId(it.id) == normalizePrivacyUserId(userId) }
            ?.let { return Result.success(it) }
        return try {
            var normalizedUserId = normalizePrivacyUserId(userId)
            var currentUserId = authService.getCurrentUserId()?.let(::normalizePrivacyUserId)
            if (!forceNetwork) {
                if (normalizedUserId == currentUserId) {
                    getLocalProfile()
                        ?.takeIf { normalizePrivacyUserId(it.id) == normalizedUserId }
                        ?.let { return Result.success(it) }
                }
                userDao.getUser(normalizedUserId)?.toDomain()?.let { cachedUser ->
                    return Result.success(applyLocalUserOverrides(cachedUser))
                }
            }

            var token = authService.getAccessTokenAwait()
            var remoteLookupWasEmpty = false
            var remoteFailure: Throwable? = null

            if (
                shouldUseUbuntuBackend() &&
                currentUserId != null &&
                normalizedUserId == currentUserId
            ) {
                val localResult = loadCurrentLocalFallbackProfile()
                localResult.onSuccess { localUser -> return Result.success(localUser) }

                getLocalProfile()?.takeIf { normalizePrivacyUserId(it.id) == normalizedUserId }?.let { localUser ->
                    return Result.success(localUser)
                }
                userDao.getUser(normalizedUserId)?.toDomain()?.let { cachedUser ->
                    return Result.success(applyLocalUserOverrides(cachedUser))
                }

                return Result.failure(
                    localResult.exceptionOrNull()
                        ?: Exception("No se pudo cargar el perfil desde Ubuntu")
                )
            }

            if (shouldUseUbuntuBackend()) {
                if (token == null) {
                    return Result.failure(Exception("No hay token local para cargar el perfil desde Ubuntu"))
                }
                localFallbackApiClient.loadProfileUser(token, normalizedUserId)
                    .onSuccess { remoteUser -> return Result.success(remoteUser) }
                    .onFailure { throwable -> remoteFailure = throwable }

                userDao.getUser(normalizedUserId)?.toDomain()?.let { cachedUser ->
                    return Result.success(applyLocalUserOverrides(cachedUser))
                }
                return Result.failure(
                    remoteFailure ?: Exception("No se pudo cargar el perfil desde Ubuntu")
                )
            }

            if (token != null) {
                fetchRemoteUserById(normalizedUserId, token, currentUserId)
                    .onSuccess { remoteUser ->
                        if (remoteUser != null) {
                            return Result.success(remoteUser)
                        }
                        remoteLookupWasEmpty = true
                    }
                    .onFailure { throwable ->
                        remoteFailure = throwable
                    }
            }

            if (normalizedUserId == currentUserId && (remoteLookupWasEmpty || remoteFailure != null)) {
                ensureCurrentProfile()
                    .onSuccess { repairedUser ->
                        return Result.success(repairedUser)
                    }
                    .onFailure { throwable ->
                        if (remoteFailure == null) {
                            remoteFailure = throwable
                        }
                    }
            }

            if (normalizedUserId == currentUserId) {
                val cachedUser = getLocalProfile()
                if (cachedUser != null) {
                    return Result.success(cachedUser)
                }
            }

            var cachedEntity = userDao.getUser(normalizedUserId)
            if (cachedEntity != null) {
                android.util.Log.d("UserRepository", "Returning cached profile from DAO")
                return Result.success(applyLocalUserOverrides(cachedEntity.toDomain()))
            }

            if (remoteLookupWasEmpty && normalizedUserId != currentUserId) {
                Result.success(null)
            } else if (token == null) {
                Result.failure(Exception("No access token and no cache"))
            } else {
                Result.failure(remoteFailure ?: Exception("Error loading profile"))
            }
        } catch (e: Exception) {
             try {
                var normalizedUserId = normalizePrivacyUserId(userId)
                val currentUserId = authService.getCurrentUserId()?.let(::normalizePrivacyUserId)
                if (normalizedUserId == currentUserId) {
                    getLocalProfile()?.let { return Result.success(it) }
                }
                var cachedEntity = userDao.getUser(normalizedUserId)
                if (cachedEntity != null) {
                    return Result.success(applyLocalUserOverrides(cachedEntity.toDomain()))
                }
            } catch (e2: Exception) { }
            
            Result.failure(e)
        }
    }

    suspend fun getCachedUserSnapshot(userId: String): User? {
        var normalizedUserId = normalizePrivacyUserId(userId)
        var currentUserId = authService.getCurrentUserId()?.let(::normalizePrivacyUserId)

        if (normalizedUserId == currentUserId) {
            getLocalProfile()?.let { return it }
        }

        return runCatching {
            userDao.getUser(normalizedUserId)
                ?.toDomain()
                ?.let(::applyLocalUserOverrides)
        }.getOrNull()
    }

    private fun saveProfileLocally(user: User) {
        localStateStore.saveProfile(user)
    }

    private fun getLocalProfileRaw(): User? {
        return localStateStore.getLocalProfileRaw()
    }

    private fun getLocalProfile(): User? {
        return localStateStore.getLocalProfile()
    }

    private fun applyLocalUserOverrides(user: User): User {
        return localStateStore.applyLocalUserOverrides(user)
    }

    /**
     * Clear all locally cached user profile data.
     */
    fun clearData() {
        android.util.Log.w("UserRepository", "CLEAR DATA: Purging local profile cache...")
        localStateStore.clearAll()
    }

    suspend fun getAdditionalLoginEmails(): Result<List<String>> {
        return try {
            var token = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))

            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profile_login_emails") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("user_id", "eq.$userId")
                parameter("select", "email")
                parameter("order", "created_at.asc")
            }

            if (response.status.isSuccess()) {
                var rows = response.body<List<JsonObject>>()
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
            var token = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            var normalized = email.trim().lowercase()

            var payload = buildJsonObject {
                put("user_id", userId)
                put("email", normalized)
            }

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/profile_login_emails") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                header("Prefer", "return=minimal")
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                var body = runCatching { response.bodyAsText() }.getOrDefault("")
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
            var token = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            var normalized = email.trim().lowercase()

            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/profile_login_emails") {
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

    suspend fun deleteOwnAccount(): Result<Unit> {
        return try {
            var token = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            authService.getCurrentUserId()?.trim()?.takeIf { it.isNotBlank() }
                ?: return Result.failure(Exception("No user"))

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/delete_own_account") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                setBody(buildJsonObject {})
            }
            val body = runCatching { response.bodyAsText() }.getOrDefault("")

            if (response.status.isSuccess()) {
                localStateStore.clearAll()
                Result.success(Unit)
            } else if (isMissingRpcFunction(response.status.value, body, "delete_own_account")) {
                // Keep the UI honest on databases that have not applied the production RPC yet.
                Result.failure(Exception("Falta aplicar la migración SQL delete_own_account para eliminar la cuenta de forma segura."))
            } else {
                Result.failure(Exception("No se pudo eliminar la cuenta (${response.status.value}): $body"))
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "deleteOwnAccount exception", e)
            Result.failure(e)
        }
    }

    private fun isMissingRpcFunction(statusCode: Int, body: String, functionName: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val cleanBody = body.lowercase()
        return cleanBody.contains(functionName.lowercase()) &&
            (cleanBody.contains("could not find the function") || cleanBody.contains("undefined function"))
    }

    suspend fun getUsersByIds(userIds: List<String>): Result<List<User>> {
        val normalizedIds = userIds
            .map(::normalizePrivacyUserId)
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedIds.isEmpty()) return Result.success(emptyList())

        val cachedUsers = normalizedIds.mapNotNull { userId ->
            runCatching { userDao.getUser(userId)?.toDomain() }
                .getOrNull()
                ?.let(::applyLocalUserOverrides)
        }

        return try {
            var token = authService.getAccessTokenAwait()
                ?: return if (cachedUsers.isNotEmpty()) {
                    Result.success(cachedUsers)
                } else {
                    Result.failure(Exception("No access token"))
                }

            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("id", "in.(${normalizedIds.joinToString(",")})")
            }

            if (response.status.isSuccess()) {
                val remoteUsers = response.body<List<User>>().map(::applyLocalUserOverrides)
                val remoteIds = remoteUsers.map { normalizePrivacyUserId(it.id) }.toSet()
                val mergedUsers = (remoteUsers + cachedUsers.filterNot { remoteIds.contains(normalizePrivacyUserId(it.id)) })
                // Save all to Room
                userDao.insertUsers(mergedUsers.map { conectate.com.data.local.entity.UserEntity.fromDomain(it) })
                Result.success(mergedUsers)
            } else {
                if (cachedUsers.isNotEmpty()) {
                    Result.success(cachedUsers)
                } else {
                    Result.failure(Exception("Error loading profiles"))
                }
            }
        } catch (e: Exception) {
            if (cachedUsers.isNotEmpty()) {
                Result.success(cachedUsers)
            } else {
                Result.failure(e)
            }
        }
    }

    // ─────────────────────────────────────────────
    // CREATE PROFILE (AFTER SIGNUP)
    // ─────────────────────────────────────────────
    suspend fun createProfile(user: User, privacyPolicyAccepted: Boolean = false): Result<Unit> {
        return try {
            var token = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token"))
            var acceptedAt = if (privacyPolicyAccepted) java.time.OffsetDateTime.now().toString() else null

            val preparedUser = prepareProfileForPersistence(user)
            val usernameCandidates = linkedSetOf(
                preparedUser.username.orEmpty(),
                buildDeterministicUsername(preparedUser.id)
            ).filter { it.isNotBlank() }

            var finalResponse: HttpResponse? = null
            var finalUser = preparedUser
            var finalError = ""

            for (usernameCandidate in usernameCandidates) {
                finalUser = preparedUser.copy(username = usernameCandidate)
                var response = submitProfileUpsert(
                    token = token,
                    body = buildProfilePayload(
                        user = finalUser,
                        privacyPolicyAccepted = privacyPolicyAccepted,
                        acceptedAt = acceptedAt
                    )
                )
                var responseError = if (response.status.isSuccess()) "" else response.bodyAsText()

                if (!response.status.isSuccess() && privacyPolicyAccepted && isMissingPrivacyAcceptedColumn(responseError)) {
                    response = submitProfileUpsert(
                        token = token,
                        body = buildProfilePayload(
                            user = finalUser,
                            privacyPolicyAccepted = false,
                            acceptedAt = null
                        )
                    )
                    responseError = if (response.status.isSuccess()) "" else response.bodyAsText()
                }

                if (response.status.isSuccess()) {
                    finalResponse = response
                    finalError = ""
                    break
                }

                finalError = responseError
                if (!isDuplicateUsernameError(responseError)) {
                    break
                }
            }

            var response = finalResponse
            if (response != null && response.status.isSuccess()) {
                val createdUser = runCatching {
                    var rawBody = response.bodyAsText()
                    supabaseConfig.json.decodeFromString<List<User>>(rawBody).firstOrNull()
                }.getOrNull()
                    ?.let(::applyLocalUserOverrides)
                    ?: applyLocalUserOverrides(
                        finalUser.copy(
                            privacy_policy_accepted = privacyPolicyAccepted,
                            privacy_policy_accepted_at = acceptedAt
                        )
                    )

                userDao.insertUser(conectate.com.data.local.entity.UserEntity.fromDomain(createdUser))
                var currentUserId = authService.getCurrentUserId()?.let(::normalizePrivacyUserId)
                if (currentUserId == null || normalizePrivacyUserId(createdUser.id) == currentUserId) {
                    saveProfileLocally(createdUser)
                }
                if (privacyPolicyAccepted && acceptedAt != null) {
                    localStateStore.setLocalPrivacyAccepted(createdUser.id, acceptedAt, pendingSync = false)
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Create profile failed: ${finalError.ifBlank { "Unknown error" }}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun hasAcceptedPrivacyPolicy(userId: String? = authService.getCurrentUserId()): Result<Boolean> {
        return try {
            var uid = userId ?: return Result.failure(Exception("Usuario no identificado"))
            var normalized = normalizePrivacyUserId(uid)
            var sessionSeed = authService.getCurrentUserProfileSeed()
                ?.takeIf { normalizePrivacyUserId(it.id) == normalized }
            if (localStateStore.getLocalPrivacyAccepted(uid)) {
                if (localStateStore.isPrivacySyncPending(uid)) {
                    runCatching { syncPrivacyAcceptance(uid) }
                }
                return Result.success(true)
            }
            var token = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("Sesión expirada. Inicia sesión de nuevo."))
            suspend fun attemptFetch(accessToken: String): Pair<HttpResponse, String> {
                var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Authorization", "Bearer $accessToken")
                    parameter("id", "eq.$uid")
                    parameter("select", "privacy_policy_accepted,privacy_policy_accepted_at")
                    parameter("limit", 1)
                }
                var errBody = runCatching { response.bodyAsText() }.getOrDefault("")
                return response to errBody
            }

            var (response, err) = attemptFetch(token)

            if (isJwtExpiredError(response.status, err)) {
                var refreshedToken = refreshAccessToken()
                if (!refreshedToken.isNullOrBlank()) {
                    token = refreshedToken
                    var retry = attemptFetch(token)
                    response = retry.first
                    err = retry.second
                }
            }

            if (!response.status.isSuccess()) {
                val missingPrivacyColumn =
                    err.contains("privacy_policy_accepted", ignoreCase = true) &&
                        err.contains("does not exist", ignoreCase = true)
                if (missingPrivacyColumn) {
                    var acceptedAt = java.time.OffsetDateTime.now().toString()
                    localStateStore.setLocalPrivacyAccepted(normalized, acceptedAt, pendingSync = false)
                    return Result.success(true)
                }
                return Result.failure(
                    Exception(
                        if (isJwtExpiredError(response.status, err)) {
                            "Sesión expirada. Inicia sesión de nuevo."
                        } else {
                            "No se pudo validar políticas (${response.status.value})"
                        }
                    )
                )
            }

            var rows = response.body<List<JsonObject>>()
            val row = rows.firstOrNull()
            var accepted = row
                ?.get("privacy_policy_accepted")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.equals("true", ignoreCase = true) == true
            if (!accepted && sessionSeed?.privacyPolicyAccepted == true) {
                var acceptedAt = sessionSeed.privacyPolicyAcceptedAt
                    ?: java.time.OffsetDateTime.now().toString()
                localStateStore.setLocalPrivacyAccepted(normalized, acceptedAt, pendingSync = true)
                runCatching { syncPrivacyAcceptance(uid) }
                return Result.success(true)
            }
            if (accepted) {
                var acceptedAt = row
                    ?.get("privacy_policy_accepted_at")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: java.time.OffsetDateTime.now().toString()
                localStateStore.setLocalPrivacyAccepted(normalized, acceptedAt, pendingSync = false)
            }
            Result.success(accepted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acceptPrivacyPolicy(userId: String? = authService.getCurrentUserId()): Result<Unit> {
        return try {
            var uid = userId ?: return Result.failure(Exception("Usuario no identificado"))
            var acceptedAt = java.time.OffsetDateTime.now().toString()
            localStateStore.setLocalPrivacyAccepted(uid, acceptedAt, pendingSync = true)

            var token = authService.getAccessTokenAwait()
            if (token.isNullOrBlank()) {
                android.util.Log.w("UserRepository", "acceptPrivacyPolicy: No token. Keeping local acceptance queued.")
                return Result.success(Unit)
            }

            suspend fun attemptPatch(accessToken: String): Pair<HttpResponse, String> {
                var payload = buildJsonObject {
                    put("privacy_policy_accepted", JsonPrimitive(true))
                    put("privacy_policy_accepted_at", JsonPrimitive(acceptedAt))
                }
                var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Authorization", "Bearer $accessToken")
                    header("Content-Type", "application/json")
                    parameter("id", "eq.$uid")
                    setBody(payload)
                }
                var errBody = runCatching { response.bodyAsText() }.getOrDefault("")
                return response to errBody
            }

            var (response, errBody) = attemptPatch(token)

            // If session expired, try a single refresh + retry.
            if (isJwtExpiredError(response.status, errBody)) {
                var refreshedToken = refreshAccessToken()
                if (!refreshedToken.isNullOrBlank()) {
                    token = refreshedToken
                    var retry = attemptPatch(token)
                    response = retry.first
                    errBody = retry.second
                }
            }

            if (response.status.isSuccess()) {
                localStateStore.clearPrivacyPending(uid)
                syncPrivacyAcceptanceToAuthMetadata(acceptedAt)
                Result.success(Unit)
            } else {
                if (isMissingPrivacyColumn(errBody)) {
                    // Backward-compat: if column missing, treat as accepted to avoid blocking the user.
                    localStateStore.clearPrivacyPending(uid)
                    syncPrivacyAcceptanceToAuthMetadata(acceptedAt)
                    Result.success(Unit)
                } else {
                    android.util.Log.w(
                        "UserRepository",
                        "Privacy acceptance queued (${response.status.value}): ${errBody.take(200)}"
                    )
                    syncPrivacyAcceptanceToAuthMetadata(acceptedAt)
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Privacy acceptance failed, keeping local acceptance queued", e)
            Result.success(Unit)
        }
    }

    private suspend fun syncPrivacyAcceptanceToAuthMetadata(acceptedAt: String) {
        authService.updateUserMetadata(
            fullName = null,
            avatarUrl = null,
            displayName = null,
            country = null,
            privacyPolicyAccepted = true,
            privacyPolicyAcceptedAt = acceptedAt
        ).onFailure {
            android.util.Log.w("UserRepository", "Failed to sync privacy acceptance to auth metadata", it)
        }
    }

    private suspend fun syncPrivacyAcceptance(userId: String): Boolean {
        if (!localStateStore.isPrivacySyncPending(userId)) return false
        var acceptedAt = localStateStore.getLocalPrivacyAcceptedAt(userId) ?: java.time.OffsetDateTime.now().toString()
        var token = authService.getAccessTokenAwait() ?: return false

        suspend fun attemptPatch(accessToken: String): Pair<HttpResponse, String> {
            var payload = buildJsonObject {
                put("privacy_policy_accepted", JsonPrimitive(true))
                put("privacy_policy_accepted_at", JsonPrimitive(acceptedAt))
            }
            var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                parameter("id", "eq.$userId")
                setBody(payload)
            }
            var errBody = runCatching { response.bodyAsText() }.getOrDefault("")
            return response to errBody
        }

        var (response, errBody) = attemptPatch(token)

        if (isJwtExpiredError(response.status, errBody)) {
            var refreshedToken = refreshAccessToken()
            if (!refreshedToken.isNullOrBlank()) {
                token = refreshedToken
                var retry = attemptPatch(token)
                response = retry.first
                errBody = retry.second
            }
        }

        if (response.status.isSuccess() || isMissingPrivacyColumn(errBody)) {
            localStateStore.clearPrivacyPending(userId)
            return true
        }

        return false
    }

    private fun isJwtExpiredError(status: HttpStatusCode, body: String): Boolean {
        if (status != HttpStatusCode.Unauthorized && status.value != 401) return false
        var lower = body.lowercase()
        return lower.contains("jwt expired") ||
            lower.contains("pgrst303") ||
            lower.contains("invalid jwt") ||
            lower.contains("token is expired") ||
            lower.contains("expired token")
    }

    private fun isMissingPrivacyColumn(body: String): Boolean {
        var lower = body.lowercase()
        return lower.contains("privacy_policy_accepted") && lower.contains("does not exist")
    }

    private fun privacyPolicyErrorMessage(status: HttpStatusCode, body: String): String {
        return when {
            isJwtExpiredError(status, body) -> "Sesión expirada. Inicia sesión de nuevo."
            status == HttpStatusCode.Unauthorized -> "Sesión inválida. Inicia sesión de nuevo."
            status == HttpStatusCode.Forbidden -> "No tienes permisos para aceptar las políticas."
            else -> "No se pudo aceptar políticas (${status.value}). Intenta de nuevo."
        }
    }

    private fun profileLoadErrorMessage(status: HttpStatusCode, body: String): String {
        return when {
            isJwtExpiredError(status, body) -> "Sesión expirada. Inicia sesión de nuevo."
            status == HttpStatusCode.Unauthorized -> "Sesión inválida. Inicia sesión de nuevo."
            status == HttpStatusCode.Forbidden -> "No tienes permisos para ver este perfil."
            status == HttpStatusCode.NotFound -> "No encontramos ese perfil."
            else -> "No se pudo cargar el perfil (${status.value}). Intenta de nuevo."
        }
    }

    private suspend fun refreshAccessToken(): String? {
        return try {
            supabaseConfig.client.auth.refreshCurrentSession()
            authService.getAccessToken()
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Refresh token failed", e)
            null
        }
    }

    suspend fun getUserByEmail(email: String): Result<User?> {
        return try {
            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("email", "eq.$email")
                parameter("select", "*")
                parameter("limit", 1)
            }
            if (response.status.isSuccess()) {
                var users = response.body<List<User>>()
                Result.success(users.firstOrNull())
            } else Result.failure(Exception("Fetch failed ${response.status}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getUserByPhone(phone: String): Result<User?> {
        return try {
            val normalizedPhone = phone.trim().replace(Regex("[^0-9+]"), "")
            if (normalizedPhone.isBlank()) return Result.success(null)

            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val directResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("phone", "eq.$normalizedPhone")
                parameter("select", "*")
                parameter("limit", 1)
            }
            if (directResponse.status.isSuccess()) {
                var users = directResponse.body<List<User>>()
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
                    var users = fallbackResponse.body<List<User>>()
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
            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("username", "eq.$username")
                parameter("select", "*")
                parameter("limit", 1)
            }
            if (response.status.isSuccess()) {
                var users = response.body<List<User>>()
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
            var token = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token"))
            var normalizedUserId = normalizePrivacyUserId(userId)

            if (request.is_private != null || request.profile_visibility != null) {
                localStateStore.setLocalProfileVisibility(
                    userId = normalizedUserId,
                    isPrivate = request.is_private,
                    profileVisibility = request.profile_visibility
                )
            }

            if (shouldUseUbuntuBackend()) {
                val response = localFallbackApiClient.updateCurrentProfile(
                    accessToken = token,
                    displayName = request.display_name,
                    bio = request.bio,
                    country = request.country,
                    city = request.city,
                    gender = request.gender,
                    birthDate = request.birth_date,
                    status = request.status,
                    interests = request.interests,
                    hobbies = request.hobbies,
                    linkedGames = request.linked_games,
                    gameLogoUrls = request.game_logo_urls,
                    favoriteMusicUrl = request.favorite_music_url,
                    favoriteBook = request.favorite_book,
                    favoriteSport = request.favorite_sport,
                    career = request.career,
                    availabilitySchedule = request.availability_schedule,
                    profileSectionOrder = request.profile_section_order,
                    profileSectionVisibility = request.profile_section_visibility,
                    avatarUrl = request.avatar_url,
                    avatarMediaType = request.avatar_media_type,
                    avatarVideoMuted = request.avatar_video_muted,
                    avatarDurationMs = request.avatar_duration_ms,
                    coverUrl = request.cover_url,
                    removeAvatar = request.remove_avatar,
                    removeCover = request.remove_cover,
                    profileTheme = request.profile_theme,
                    profileEffect = request.profile_effect,
                    profileWelcome = request.profile_welcome,
                    isPrivate = request.is_private,
                    favoritesPublic = request.favorites_public,
                    breakMode = request.break_mode,
                    profileVisibility = request.profile_visibility,
                    lastSeenVisibility = request.last_seen_visibility,
                    onlineStatusVisibility = request.online_status_visibility
                )

                response.onSuccess { profileResponse ->
                    val updatedUser = mapLocalFallbackProfile(
                        response = profileResponse,
                        fallbackId = normalizedUserId,
                        fallbackEmail = authService.getCurrentUserEmail()
                    )
                    if (updatedUser == null) {
                        return Result.failure(Exception("El servidor no devolvió el perfil actualizado"))
                    }
                    profileUpdateMismatch(request, updatedUser)?.let { mismatch ->
                        return Result.failure(Exception("El servidor no confirmó el campo actualizado: $mismatch"))
                    }
                    userDao.insertUser(conectate.com.data.local.entity.UserEntity.fromDomain(updatedUser))
                    saveProfileLocally(updatedUser)
                    _profileUpdates.emit(updatedUser)
                    return Result.success(Unit)
                }

                return Result.failure(
                    response.exceptionOrNull()
                        ?: Exception("No se pudo actualizar el perfil en Ubuntu")
                )
            }

            // ✅ ENFORCE 7-DAY NAME CHANGE COOLDOWN
            if (request.display_name != null) {
                // Get current user profile to check last_name_change
                val userResult = getUserById(normalizedUserId)
                if (userResult.isSuccess) {
                    var user = userResult.getOrNull()
                    var lastNameChange = user?.last_name_change

                    if (lastNameChange != null) {
                        try {
                            val lastChangeDate = java.time.LocalDateTime.parse(lastNameChange)
                            var now = java.time.LocalDateTime.now()
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
            val effectiveLastNameChange = if (request.display_name != null) {
                java.time.LocalDateTime.now().toString()
            } else {
                request.last_name_change
            }
            val payloadFields = buildProfileUpdateFields(request, effectiveLastNameChange)

            android.util.Log.d(
                "UserRepository",
                "Updating profile fields displayName=${request.display_name != null}, avatar=${request.avatar_url != null}, fieldCount=${payloadFields.size}"
            )

            if (payloadFields.isEmpty()) {
                return Result.success(Unit)
            }

            suspend fun submitPatch(fields: Map<String, JsonElement>): HttpResponse {
                return supabaseConfig.httpClient.patch(
                    "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
                ) {
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Authorization", "Bearer $token")
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    parameter("id", "eq.$normalizedUserId")
                    setBody(JsonObject(fields))
                }
            }

            var response = submitPatch(payloadFields)
            var responseBody = runCatching { response.bodyAsText() }.getOrDefault("")

            if (!response.status.isSuccess()) {
                extractMissingProfilesColumn(responseBody)?.let { missingColumn ->
                    if (payloadFields.containsKey(missingColumn)) {
                        return Result.failure(
                            Exception("El servidor no tiene la columna de perfil requerida: $missingColumn")
                        )
                    }
                }
            }

            if (response.status.isSuccess()) {
                android.util.Log.d("UserRepository", "Profile updated successfully")

                val updatedUser = runCatching {
                    if (responseBody.isBlank()) {
                        null
                    } else {
                        supabaseConfig.json.decodeFromString<List<User>>(responseBody)
                            .firstOrNull()
                            ?.let(::applyLocalUserOverrides)
                    }
                }.getOrNull()
                    ?: if (normalizedUserId == authService.getCurrentUserId()?.let(::normalizePrivacyUserId)) {
                        getUserById(normalizedUserId).getOrNull()
                    } else {
                        null
                    }

                if (updatedUser != null) {
                    profileUpdateMismatch(request, updatedUser)?.let { mismatch ->
                        return Result.failure(Exception("El servidor no confirmó el campo actualizado: $mismatch"))
                    }
                    userDao.insertUser(conectate.com.data.local.entity.UserEntity.fromDomain(updatedUser))
                    if (updatedUser.id == authService.getCurrentUserId()) {
                        saveProfileLocally(updatedUser)
                    }
                    _profileUpdates.emit(updatedUser)
                } else {
                    return Result.failure(Exception("El servidor respondió sin una fila de perfil actualizada"))
                }
                
                // ✅ SYNC METADATA TO PREVENT RESET ON RE-LOGIN
                authService.updateUserMetadata(
                    fullName = request.display_name,
                    avatarUrl = request.avatar_url,
                    displayName = request.display_name,
                    country = request.country
                )
                
                Result.success(Unit)
            } else {
                android.util.Log.e("UserRepository", "Update failed ${response.status}: $responseBody")
                Result.failure(Exception("Update failed ${response.status}: $responseBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Update profile exception", e)
            Result.failure(e)
        }
    }

    suspend fun updatePresenceVisibility(
        userId: String,
        lastSeenVisibility: String? = null,
        onlineStatusVisibility: String? = null
    ): Result<Boolean> {
        var normalizedLastSeen = lastSeenVisibility?.let(localStateStore::normalizePresenceVisibility)
        var normalizedOnline = onlineStatusVisibility?.let(localStateStore::normalizePresenceVisibility)
        if (normalizedLastSeen == null && normalizedOnline == null) {
            return Result.success(true)
        }

        localStateStore.setLocalPresenceVisibility(
            userId = userId,
            lastSeenVisibility = normalizedLastSeen,
            onlineStatusVisibility = normalizedOnline
        )

        return try {
            var token = authService.getAccessTokenAwait()
            if (token.isNullOrBlank()) {
                return Result.success(false)
            }

            suspend fun attemptPatch(accessToken: String): Pair<HttpResponse, String> {
                var payload = buildJsonObject {
                    normalizedLastSeen?.let { put("last_seen_visibility", JsonPrimitive(it)) }
                    normalizedOnline?.let { put("online_status_visibility", JsonPrimitive(it)) }
                }
                var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Authorization", "Bearer $accessToken")
                    header("Content-Type", "application/json")
                    header("Prefer", "return=minimal")
                    parameter("id", "eq.$userId")
                    setBody(payload)
                }
                var responseBody = runCatching { response.bodyAsText() }.getOrDefault("")
                return response to responseBody
            }

            var (response, responseBody) = attemptPatch(token)
            if (isJwtExpiredError(response.status, responseBody)) {
                var refreshedToken = refreshAccessToken()
                if (!refreshedToken.isNullOrBlank()) {
                    token = refreshedToken
                    var retry = attemptPatch(token)
                    response = retry.first
                    responseBody = retry.second
                }
            }

            if (response.status.isSuccess()) {
                Result.success(true)
            } else {
                android.util.Log.w(
                    "UserRepository",
                    "Presence visibility saved locally but remote sync failed (${response.status.value}): ${responseBody.take(200)}"
                )
                Result.success(false)
            }
        } catch (e: Exception) {
            android.util.Log.w("UserRepository", "Presence visibility stored locally after sync failure", e)
            Result.success(false)
        }
    }

    // ─────────────────────────────────────────────
    // UPLOAD PROFILE IMAGE
    // ─────────────────────────────────────────────
    suspend fun uploadProfileImage(uri: Uri): Result<String> {
        return uploadImage(uri, "avatars", "_profile")
    }

    suspend fun uploadProfileAvatarMedia(uri: Uri): Result<ProfileAvatarUpload> {
        return try {
            val userId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))
            val bytes = context.contentResolver
                .openInputStream(uri)
                ?.use { it.readBytes() }
                ?: return Result.failure(Exception("No se pudo leer el archivo."))
            val hintedMime = context.contentResolver.getType(uri)
                ?.substringBefore(";")
                ?.trim()
                ?.lowercase()
            val inferredMime = inferProfileAvatarMimeType(bytes)
            val mimeType = when {
                inferredMime != null -> inferredMime
                hintedMime == "image/gif" -> "image/gif"
                hintedMime?.startsWith("video/") == true -> "video/mp4"
                hintedMime in setOf("image/jpeg", "image/png", "image/webp") -> hintedMime
                else -> null
            } ?: return Result.failure(Exception("Usa JPG, PNG, WEBP, GIF o un video MP4."))

            if (mimeType == "image/gif") {
                val durationMs = gifDurationMs(bytes).coerceAtLeast(PROFILE_AVATAR_MAX_DURATION_MS)
                if (durationMs > PROFILE_AVATAR_MAX_DURATION_MS) {
                    return Result.failure(Exception("El GIF dura ${durationMs / 1000.0}s. El máximo para perfil es 2s."))
                }
                return uploadRawAvatarMedia(
                    fileName = "$userId/${UUID.randomUUID()}_profile.gif",
                    bytes = bytes,
                    mimeType = "image/gif",
                    mediaType = "gif",
                    durationMs = durationMs
                )
            }

            if (mimeType == "video/mp4") {
                val durationMs = getVideoDurationMs(uri)
                if (durationMs > PROFILE_AVATAR_MAX_DURATION_MS + 150L) {
                    return Result.failure(Exception("Recorta el video a máximo 2s antes de subirlo."))
                }
                return uploadRawAvatarMedia(
                    fileName = "$userId/${UUID.randomUUID()}_profile.mp4",
                    bytes = bytes,
                    mimeType = "video/mp4",
                    mediaType = "video",
                    durationMs = durationMs.takeIf { it > 0L }?.coerceAtMost(PROFILE_AVATAR_MAX_DURATION_MS)
                )
            }

            uploadImage(uri, "avatars", "_profile").map { url ->
                ProfileAvatarUpload(url = url, mediaType = "image", durationMs = null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteUploadedProfileMedia(url: String): Result<Boolean> {
        val result = storageService.deleteFileByPublicUrl(url)
        return if (result.isSuccess) Result.success(true) else Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
    }

    /**
     * Streams a user-selected song into the dedicated public profile-audio
     * bucket. A content:// URI is never persisted in the profile because it is
     * only readable on the phone that selected it.
     */
    suspend fun uploadProfileMusic(
        uri: Uri,
        contentTypeHint: String? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val userId = authService.getCurrentUserId()
                ?: return@withContext Result.failure(Exception("No hay una sesión activa."))
            val resolver = context.contentResolver

            var reportedSize = -1L
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            reportedSize = cursor.getLong(sizeIndex)
                        }
                    }
                }
            }

            val header = resolver.openInputStream(uri)?.use { input ->
                val bytes = ByteArray(512)
                val count = input.read(bytes)
                if (count > 0) bytes.copyOf(count) else ByteArray(0)
            } ?: return@withContext Result.failure(Exception("No se pudo abrir la canción seleccionada."))

            val declaredType = canonicalizeMediaContentType(
                contentTypeHint ?: resolver.getType(uri)
            )
            val rawInferredType = canonicalizeMediaContentType(inferMimeTypeFromBytes(header))
            // Media3 exports an audio-only MP4 with a generic ISO brand on some
            // devices. The byte sniffer can label that container as video/mp4;
            // the trusted exporter hint preserves its real audio type.
            val inferredType = if (
                declaredType == "audio/mp4" &&
                rawInferredType == "video/mp4"
            ) {
                "audio/mp4"
            } else {
                rawInferredType
            }
            val allowedTypes = setOf(
                "audio/mpeg",
                "audio/mp4",
                "audio/wav",
                "audio/ogg",
                "audio/webm",
                "audio/flac"
            )
            if (inferredType != null && inferredType !in allowedTypes) {
                return@withContext Result.failure(Exception("El archivo seleccionado no es una canción compatible."))
            }
            val contentType = inferredType?.takeIf { it in allowedTypes }
                ?: declaredType?.takeIf { it in allowedTypes }
                ?: return@withContext Result.failure(
                    Exception("Formato no compatible. Usa MP3, M4A, WAV, OGG, WebM/Opus o FLAC.")
                )
            val extension = preferredExtensionForMime(contentType)
                ?: return@withContext Result.failure(Exception("No se pudo identificar el formato de audio."))

            val descriptorSize = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length }
            }.getOrNull()?.takeIf { it > 0L }
            val measuredSize = when {
                reportedSize > 0L -> reportedSize
                descriptorSize != null -> descriptorSize
                else -> resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > PROFILE_MUSIC_MAX_BYTES) break
                    }
                    total
                } ?: -1L
            }
            if (measuredSize <= 0L) {
                return@withContext Result.failure(Exception("No se pudo comprobar el tamaño de la canción."))
            }
            if (measuredSize > PROFILE_MUSIC_MAX_BYTES) {
                return@withContext Result.failure(Exception("La canción supera el máximo permitido de 40 MB."))
            }

            val requestedPath = "$userId/${UUID.randomUUID()}.$extension"
            val upload = storageService.uploadFileFromUri(
                bucketName = PROFILE_MUSIC_BUCKET,
                fileName = requestedPath,
                sourceUri = uri,
                sizeBytes = measuredSize,
                contentType = contentType,
                allowOverwrite = false,
                onProgress = onProgress
            )
            val stored = upload.getOrElse { error ->
                return@withContext Result.failure(error)
            }
            val storedPath = ProfileMediaSync.uploadedObjectPath(
                bucket = PROFILE_MUSIC_BUCKET,
                requestedPath = requestedPath,
                responseKey = stored.Key,
                responseName = stored.Name
            )
            val publicUrl = UbuntuStorageUrl.rewrite(
                storageService.getPublicUrl(PROFILE_MUSIC_BUCKET, storedPath)
            ).orEmpty()
            if (publicUrl.isBlank()) {
                return@withContext Result.failure(Exception("El servidor no devolvió la URL de la canción."))
            }
            Result.success(publicUrl)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deleteUploadedProfileMusic(url: String): Result<Boolean> {
        val normalized = url.trim().replace("\\", "/")
        val marker = "/storage/v1/object/public/$PROFILE_MUSIC_BUCKET/"
        if (!normalized.contains(marker, ignoreCase = true)) {
            return Result.success(false)
        }
        return storageService.deleteFileByPublicUrl(normalized).map { true }
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
            var userId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            var bytes = context.contentResolver
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
                val stored = upload.getOrThrow()
                val storedPath = ProfileMediaSync.uploadedObjectPath(
                    bucket = bucket,
                    requestedPath = fileName,
                    responseKey = stored.Key,
                    responseName = stored.Name
                )
                Result.success(UbuntuStorageUrl.rewrite(storageService.getPublicUrl(bucket, storedPath)).orEmpty())
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
            "image/gif" -> "gif"
            else -> "jpg"
        }
    }

    private suspend fun uploadRawAvatarMedia(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
        mediaType: String,
        durationMs: Long?
    ): Result<ProfileAvatarUpload> {
        val upload = storageService.uploadFile(
            "avatars",
            fileName,
            bytes,
            mimeType
        )
        return if (upload.isSuccess) {
            val stored = upload.getOrThrow()
            val storedPath = ProfileMediaSync.uploadedObjectPath(
                bucket = "avatars",
                requestedPath = fileName,
                responseKey = stored.Key,
                responseName = stored.Name
            )
            Result.success(
                ProfileAvatarUpload(
                    url = UbuntuStorageUrl.rewrite(storageService.getPublicUrl("avatars", storedPath)).orEmpty(),
                    mediaType = mediaType,
                    durationMs = durationMs
                )
            )
        } else {
            Result.failure(upload.exceptionOrNull() ?: Exception("No se pudo subir el avatar."))
        }
    }

    fun getProfileVideoDurationMs(uri: Uri): Long = getVideoDurationMs(uri)

    private fun getVideoDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun gifDurationMs(bytes: ByteArray): Long {
        return runCatching {
            Movie.decodeByteArray(bytes, 0, bytes.size)?.duration()?.toLong() ?: PROFILE_AVATAR_MAX_DURATION_MS
        }.getOrDefault(PROFILE_AVATAR_MAX_DURATION_MS)
    }

    private fun inferProfileAvatarMimeType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte()
        ) {
            return "image/gif"
        }
        if (
            bytes.getOrNull(4) == 0x66.toByte() &&
            bytes.getOrNull(5) == 0x74.toByte() &&
            bytes.getOrNull(6) == 0x79.toByte() &&
            bytes.getOrNull(7) == 0x70.toByte()
        ) {
            return "video/mp4"
        }
        return inferImageMimeType(bytes)
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
                var candidate = stream.toByteArray()
                lastCandidate = candidate

                if (candidate.size.toLong() <= maxBytes) {
                    return candidate
                }

                if (quality > 55) {
                    quality -= 10
                } else {
                    var nextWidth = (workingBitmap.width * 0.85f).toInt().coerceAtLeast(720)
                    var nextHeight = (workingBitmap.height * 0.85f).toInt().coerceAtLeast(720)
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
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte()
        ) {
            return "image/gif"
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
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token"))

            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("select", "*")
                parameter("limit", limit)
                parameter("order", "created_at.desc")
            }

            if (response.status.isSuccess()) {
                var users = response.body<List<User>>()
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

            if (shouldUseUbuntuBackend()) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("No access token"))
                return@withContext localFallbackApiClient.searchProfileUsers(
                    accessToken = token,
                    query = sanitizedQuery,
                    limit = 20
                ).map { users -> users.deduplicatedForSearch() }
            }

            // 1. If it's a numeric ID, try exact match and also partial matches
            val numericId = sanitizedQuery.toLongOrNull()

            var response = supabaseConfig.client.postgrest["profiles"].select {
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
            Result.success(response.deduplicatedForSearch())
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "❌ Search failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun List<User>.deduplicatedForSearch(): List<User> {
        val indexByKey = LinkedHashMap<String, Int>()
        val users = mutableListOf<User>()
        for (user in this) {
            val keys = listOf(
                user.email,
                user.username.orEmpty(),
                user.display_name.orEmpty(),
                user.full_name.orEmpty(),
                user.id
            )
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && it != "null" }
                .distinct()
            if (keys.isEmpty()) continue

            val existingIndex = keys.firstNotNullOfOrNull { indexByKey[it] }
            if (existingIndex == null) {
                val newIndex = users.size
                users += user
                keys.forEach { indexByKey[it] = newIndex }
                continue
            }

            val existing = users[existingIndex]
            val chosen = when {
                existing.avatar_url.isNullOrBlank() && !user.avatar_url.isNullOrBlank() -> user
                existing.email.isBlank() && user.email.isNotBlank() -> user
                else -> existing
            }
            users[existingIndex] = chosen
            keys.forEach { indexByKey[it] = existingIndex }
        }
        return users
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

        var status = user.status?.trim()?.lowercase().orEmpty()
        if (status in NON_MENTIONABLE_STATUSES) return false

        return true
    }

    suspend fun getUsersByFollowerThreshold(threshold: Int): Result<List<User>> {
        return try {
            var accessToken = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("followers_count", "gte.$threshold")
                parameter("order", "followers_count.desc")
                parameter("limit", 50)
            }
            if (response.status.isSuccess()) {
                 var bodyStr = response.bodyAsText()
                 var users = Json { ignoreUnknownKeys = true }.decodeFromString<List<User>>(bodyStr)
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
        val profile: User? = null
    )

    @kotlinx.serialization.Serializable
    private data class FollowingResponse(
        @kotlinx.serialization.SerialName("profiles")
        val profile: User? = null
    )

    suspend fun followUser(targetUserId: String): Result<Unit> {
        return try {
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token"))
            var currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            if (currentUserId == targetUserId) {
                return Result.failure(Exception("No te puedes seguir a ti mismo"))
            }

            // 1. Insert into follows
            val followData = mapOf(
                "follower_id" to currentUserId,
                "following_id" to targetUserId
            )
            
            var response = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                setBody(followData)
            }

            if (!response.status.isSuccess()) {
                var err = try { response.body<String>() } catch (e: Exception) { "" }
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
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token"))
            var currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            // 1. Delete from follows
            var response = supabaseConfig.httpClient.delete(
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
            var accessToken = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))

            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("follower_id", "eq.$currentUserId")
                parameter("following_id", "eq.$targetUserId")
                header("Range", "0-1")
            }

            if (response.status.isSuccess()) {
                var bodyStr = response.body<String>()
                // Robust checking using JSON parsing
                val isFollowing = try {
                    var json = Json { ignoreUnknownKeys = true }
                    var list = json.decodeFromString<List<Map<String, String>>>(bodyStr)
                    list.isNotEmpty()
                } catch (e: Exception) {
                     android.util.Log.w("UserRepository", "Error parsing checksIsFollowing: $bodyStr")
                     false
                }
                
                Result.success(isFollowing)
            } else {
                var err = response.body<String>()
                android.util.Log.e("UserRepository", "checkIsFollowing error: ${response.status} - $err")
                Result.failure(Exception("Check failed: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFollowedUserIds(targetUserIds: Collection<String>): Result<Set<String>> {
        return try {
            val cleanTargetIds = targetUserIds
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()

            if (cleanTargetIds.isEmpty()) {
                return Result.success(emptySet())
            }

            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No token"))
            var currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user"))

            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("follower_id", "eq.$currentUserId")
                parameter("following_id", "in.(${cleanTargetIds.joinToString(",")})")
                parameter("select", "following_id")
            }

            if (!response.status.isSuccess()) {
                var err = response.body<String>()
                android.util.Log.e("UserRepository", "getFollowedUserIds error: ${response.status} - $err")
                return Result.failure(Exception("Check failed: $err"))
            }

            var bodyStr = response.body<String>()
            val followedIds = try {
                var json = Json { ignoreUnknownKeys = true }
                json.decodeFromString<List<Map<String, String>>>(bodyStr)
                    .mapNotNull { it["following_id"]?.trim()?.lowercase() }
                    .filter { it.isNotBlank() }
                    .toSet()
            } catch (e: Exception) {
                android.util.Log.w("UserRepository", "Error parsing getFollowedUserIds: $bodyStr", e)
                emptySet()
            }

            Result.success(followedIds)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFollowers(userId: String): Result<List<User>> {
        return try {
            var accessToken = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))

            if (shouldUseUbuntuBackend()) {
                return localFallbackApiClient.loadFollowUsers(accessToken, userId, "followers")
            }
            
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("following_id", "eq.$userId")
                parameter("select", "profiles!follower_id(*)")
            }

             if (response.status.isSuccess()) {
                var rawBody = response.body<String>()
                var json = Json { ignoreUnknownKeys = true }
                // Parse List<FollowerResponse>
                var list = json.decodeFromString<List<FollowerResponse>>(rawBody)
                var users = list.mapNotNull { it.profile }
                Result.success(users)
            } else {
                Result.failure(Exception("Fetch failed ${response.status}"))
            } 
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getFollowing(userId: String): Result<List<User>> {
         return try {
            var accessToken = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))

            if (shouldUseUbuntuBackend()) {
                return localFallbackApiClient.loadFollowUsers(accessToken, userId, "following")
            }
            
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("follower_id", "eq.$userId")
                parameter("select", "profiles!following_id(*)")
            }

             if (response.status.isSuccess()) {
                var rawBody = response.body<String>()
                var json = Json { ignoreUnknownKeys = true }
                // Parse List<FollowingResponse>
                var list = json.decodeFromString<List<FollowingResponse>>(rawBody)
                var users = list.mapNotNull { it.profile }
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
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token"))
            var currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            if (shouldUseUbuntuBackend()) {
                return localFallbackApiClient.blockUser(accessToken, targetUserId)
                    .map {
                        invalidateBlockedMentionCache()
                        Unit
                    }
            }

            val blockData = buildJsonObject {
                put("blocker_id", currentUserId)
                put("blocked_id", targetUserId)
            }
            
            var response = supabaseConfig.httpClient.post(
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
                var err = response.body<String>()
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
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token"))
            var currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user logged in"))

            if (shouldUseUbuntuBackend()) {
                return localFallbackApiClient.unblockUser(accessToken, targetUserId)
                    .map {
                        invalidateBlockedMentionCache()
                        Unit
                    }
            }

            var response = supabaseConfig.httpClient.delete(
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
            var now = System.currentTimeMillis()
            val normalizedCurrentUserId = currentUserId.trim().lowercase()
            var cachedUserId = blockedMentionCacheUserId

            if (
                cachedUserId == normalizedCurrentUserId &&
                now - blockedMentionCacheAtMs <= blockedMentionCacheTtlMs
            ) {
                return Result.success(blockedMentionCacheIds)
            }

            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token"))

            var response = supabaseConfig.httpClient.get(
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

            var rows = response.body<List<JsonObject>>()
            val relatedIds = rows.mapNotNull { row ->
                var blockerId = row["blocker_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                var blockedId = row["blocked_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()

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
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token"))
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("No user logged in"))
            var cleanTargetId = targetUserId.trim().lowercase()
            if (cleanTargetId.isBlank()) return Result.success(BlockRelationship())

            if (shouldUseUbuntuBackend()) {
                return localFallbackApiClient.getBlockRelationship(accessToken, cleanTargetId)
                    .map { response ->
                        BlockRelationship(
                            blockedByMe = response.blockedByMe,
                            blockedMe = response.blockedMe
                        )
                    }
            }

            var response = supabaseConfig.httpClient.get(
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

            var rows = response.body<List<JsonObject>>()
            var blockedByMe = false
            var blockedMe = false
            rows.forEach { row ->
                var blockerId = row["blocker_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                var blockedId = row["blocked_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
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

    fun observeBlockRelationship(targetUserId: String): Flow<BlockRelationship> {
        val cleanTargetId = targetUserId.trim().lowercase()
        if (shouldUseUbuntuBackend()) {
            return flow {
                if (cleanTargetId.isBlank()) {
                    emit(BlockRelationship())
                    return@flow
                }
                while (true) {
                    emit(getBlockRelationship(cleanTargetId).getOrDefault(BlockRelationship()))
                    delay(5_000L)
                }
            }.distinctUntilChanged().flowOn(Dispatchers.IO)
        }

        return callbackFlow {
        var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
        if (currentUserId.isNullOrBlank()) {
            close(IllegalStateException("No user logged in"))
            return@callbackFlow
        }
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

        var channel = supabaseConfig.client.realtime.channel("blocked-users-$currentUserId-$cleanTargetId")
        var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "blocked_users"
        }

        var collectorJob = launch {
            changes.collect { action ->
                var shouldRefresh = when (action) {
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

        var subscribeJob = launch {
            supabaseConfig.subscribeRealtimeChannel(
                channel = channel,
                reason = "blocked-users-$currentUserId-$cleanTargetId"
            )
            refreshState()
        }

        awaitClose {
            collectorJob.cancel()
            subscribeJob.cancel()
        }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    private fun isBlockPairEvent(
        record: JsonObject,
        currentUserId: String,
        targetUserId: String
    ): Boolean {
        var blockerId = record["blocker_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        var blockedId = record["blocked_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        if (blockerId.isNullOrBlank() || blockedId.isNullOrBlank()) return false
        return (blockerId == currentUserId && blockedId == targetUserId) ||
            (blockerId == targetUserId && blockedId == currentUserId)
    }

    suspend fun isBlocked(targetUserId: String): Result<Boolean> {
        return getBlockRelationship(targetUserId).map { it.isBlocked }
    }

    suspend fun getVerifiedUsers(): Result<List<User>> {
        return try {
            var accessToken = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No access token"))
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("is_verified", "eq.true")
                parameter("select", "*")
            }

            if (response.status.isSuccess()) {
                var users = response.body<List<User>>()
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
            var token = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/voice_access_requests"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $token")
                parameter("user_id", "eq.$userId")
                parameter("select", "status")
            }

            android.util.Log.d("UserRepository", "Fetching request status for $userId. Response: ${response.status}")

            if (response.status.isSuccess()) {
                var bodyStr = response.bodyAsText()
                var json = Json { ignoreUnknownKeys = true }
                var list = json.decodeFromString<List<JsonObject>>(bodyStr)
                if (list.isNotEmpty()) {
                    val statusElement = list[0]["status"]
                    var status = if (statusElement is kotlinx.serialization.json.JsonPrimitive) {
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
            var token = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            
            var payload = mapOf(
                "user_id" to userId,
                "status" to "pending"
            )

            var response = supabaseConfig.httpClient.post(
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
                var err = response.bodyAsText()
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
            var token = authService.getAccessTokenAwait()
            
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                if (token != null) header("Authorization", "Bearer $token")
                parameter("is_admin", "eq.true")
                parameter("select", "*")
            }
            
            if (response.status.isSuccess()) {
                 var bodyStr = response.bodyAsText()
                 var users = Json { ignoreUnknownKeys = true }.decodeFromString<List<User>>(bodyStr)
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
            var token = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            
            var payload = mapOf(
                "reporter_id" to userId,
                "reporter_name" to userName,
                "reported_user_id" to userId,
                "reported_user_name" to userName,
                "reason" to "Solicitud de Acceso a Voz",
                "description" to "El usuario solicita acceso a la sección de salas de voz en desarrollo.",
                "status" to "pendiente"
            )
            
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/reports") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(payload)
            }
            
            if (response.status.isSuccess()) {
                android.util.Log.d("UserRepository", "Voice access report created")
                Result.success(Unit)
            } else {
                var err = response.bodyAsText()
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
            if (authService.isLocalFallbackModeEnabled()) {
                val token = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
                android.util.Log.d("UserRepository", "Setting Ubuntu online status for $userId to $isOnline")
                return localFallbackApiClient.updatePresence(token, isOnline).map { Unit }
            }
            var token = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))
            
            android.util.Log.d("UserRepository", "Setting online status for $userId to $isOnline")
            
            var response = supabaseConfig.httpClient.post(
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
                var err = response.bodyAsText()
                android.util.Log.e("UserRepository", "Failed to update online status: ${response.status} - $err")
                Result.failure(Exception("Failed to update status"))
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Exception updating online status", e)
            Result.failure(e)
        }
    }

    /**
     * Same as [setOnlineStatus], but uses an explicit access token.
     * Useful for "force logout" flows where AuthService blocks token access immediately.
     */
    suspend fun setOnlineStatusWithToken(
        userId: String,
        isOnline: Boolean,
        accessToken: String
    ): Result<Unit> {
        return try {
            if (authService.isLocalFallbackModeEnabled()) {
                var token = accessToken.trim()
                if (token.isBlank()) return Result.failure(Exception("No token"))
                android.util.Log.d("UserRepository", "Setting Ubuntu online status (explicit token) for $userId to $isOnline")
                return localFallbackApiClient.updatePresence(token, isOnline).map { Unit }
            }
            var token = accessToken.trim()
            if (token.isBlank()) return Result.failure(Exception("No token"))

            android.util.Log.d("UserRepository", "Setting online status (explicit token) for $userId to $isOnline")

            var response = supabaseConfig.httpClient.post(
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
                android.util.Log.d("UserRepository", "Online status updated successfully (explicit token)")
                Result.success(Unit)
            } else {
                var err = response.bodyAsText()
                android.util.Log.e("UserRepository", "Failed to update online status (explicit token): ${response.status} - $err")
                Result.failure(Exception("Failed to update status"))
            }
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Exception updating online status (explicit token)", e)
            Result.failure(e)
        }
    }
    
    /**
     * Observa presencia con realtime sobre `profiles` y polling liviano como respaldo.
     * Esto evita que el estado "Conectado" o "últ. vez" dependa solo de refrescos periódicos.
     */
    fun observeUserPresence(userId: String): Flow<User> = callbackFlow {
        val cleanUserId = userId.trim().lowercase()
        if (cleanUserId.isBlank()) {
            close()
            return@callbackFlow
        }
        suspend fun refreshState() {
            var user = getUserById(cleanUserId).getOrNull() ?: return
            trySend(user)
        }

        if (authService.isLocalFallbackModeEnabled()) {
            val pollingJob = launch {
                while (isActive) {
                    refreshState()
                    delay(10_000)
                }
            }
            awaitClose {
                pollingJob.cancel()
            }
            return@callbackFlow
        }

        var channel = supabaseConfig.client.realtime.channel("presence-$cleanUserId")
        var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "profiles"
            filter = "id=eq.$cleanUserId"
        }

        var collectorJob = launch {
            changes.collect { action ->
                var shouldRefresh = when (action) {
                    is PostgresAction.Insert -> true
                    is PostgresAction.Update -> true
                    is PostgresAction.Delete -> true
                    else -> false
                }
                if (shouldRefresh) {
                    refreshState()
                }
            }
        }

        var subscribeJob = launch {
            supabaseConfig.subscribeRealtimeChannel(
                channel = channel,
                reason = "presence-$cleanUserId"
            )
            refreshState()
        }

        val pollingJob = launch {
            while (isActive) {
                delay(12_000)
                refreshState()
            }
        }

        val statusJob = launch {
            channel.status.collect { status ->
                if (
                    status == RealtimeChannel.Status.UNSUBSCRIBED ||
                    status.name == "DISCONNECTED" ||
                    status.name == "TIMED_OUT" ||
                    status.name == "CLOSED"
                ) {
                    refreshState()
                }
            }
        }

        awaitClose {
            collectorJob.cancel()
            subscribeJob.cancel()
            pollingJob.cancel()
            statusJob.cancel()
            launch {
                runCatching { channel.unsubscribe() }
            }
        }
    }.distinctUntilChanged { old, new ->
        old.is_online == new.is_online &&
            old.lastSeen == new.lastSeen &&
            old.updated_at == new.updated_at
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val PROFILE_AVATAR_MAX_DURATION_MS = 2_000L
        const val PROFILE_MUSIC_BUCKET = "profile-audio"
        const val PROFILE_MUSIC_MAX_BYTES = 40L * 1024 * 1024

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





