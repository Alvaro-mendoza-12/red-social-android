package conectate.com.data.repository.user

import android.content.Context
import conectate.com.data.model.User
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class UserLocalStateStore(
    private val context: Context
) {
    companion object {
        private const val PRIVACY_PREFS = "privacy_policy_cache"
        private const val USER_CACHE_PREFS = "user_cache"
        private const val USER_CACHE_KEY = "cached_profile"
        private const val AVATAR_OWNER_KEY = "cached_avatar_owner"
        private const val AVATAR_URL_KEY = "cached_avatar_url"
    }

    private val privacyPrefs by lazy {
        context.getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE)
    }

    private val profilePrefs by lazy {
        context.getSharedPreferences(USER_CACHE_PREFS, Context.MODE_PRIVATE)
    }

    private val cacheJson = Json { ignoreUnknownKeys = true }

    fun normalizeUserId(userId: String): String = userId.trim().lowercase()

    fun getLocalPrivacyAccepted(userId: String): Boolean {
        return privacyPrefs.getBoolean(privacyAcceptedKey(userId), false)
    }

    fun getLocalPrivacyAcceptedAt(userId: String): String? {
        return privacyPrefs.getString(privacyAcceptedAtKey(userId), null)
    }

    fun isPrivacySyncPending(userId: String): Boolean {
        return privacyPrefs.getBoolean(privacyPendingKey(userId), false)
    }

    fun setLocalPrivacyAccepted(
        userId: String,
        acceptedAt: String,
        pendingSync: Boolean
    ) {
        val normalized = normalizeUserId(userId)
        privacyPrefs.edit()
            .putBoolean(privacyAcceptedKey(normalized), true)
            .putString(privacyAcceptedAtKey(normalized), acceptedAt)
            .putBoolean(privacyPendingKey(normalized), pendingSync)
            .apply()

        var cached = getLocalProfileRaw()
        if (cached != null && normalizeUserId(cached.id) == normalized) {
            saveProfile(
                cached.copy(
                    privacy_policy_accepted = true,
                    privacy_policy_accepted_at = acceptedAt
                )
            )
        }
    }

    fun clearPrivacyPending(userId: String) {
        privacyPrefs.edit()
            .putBoolean(privacyPendingKey(userId), false)
            .apply()
    }

    fun normalizePresenceVisibility(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            "contacts", "connections" -> "contacts"
            "nobody", "none" -> "nobody"
            else -> "everyone"
        }
    }

    fun setLocalPresenceVisibility(
        userId: String,
        lastSeenVisibility: String? = null,
        onlineStatusVisibility: String? = null
    ) {
        if (lastSeenVisibility == null && onlineStatusVisibility == null) return

        var normalizedUserId = normalizeUserId(userId)
        val currentCached = getLocalProfileRaw()

        privacyPrefs.edit().apply {
            lastSeenVisibility?.let {
                putString(lastSeenVisibilityKey(normalizedUserId), normalizePresenceVisibility(it))
            }
            onlineStatusVisibility?.let {
                putString(onlineStatusVisibilityKey(normalizedUserId), normalizePresenceVisibility(it))
            }
        }.apply()

        if (currentCached != null && normalizeUserId(currentCached.id) == normalizedUserId) {
            saveProfile(
                currentCached.copy(
                    last_seen_visibility = lastSeenVisibility?.let(::normalizePresenceVisibility)
                        ?: currentCached.last_seen_visibility,
                    online_status_visibility = onlineStatusVisibility?.let(::normalizePresenceVisibility)
                        ?: currentCached.online_status_visibility
                )
            )
        }
    }

    fun setLocalProfileVisibility(
        userId: String,
        isPrivate: Boolean? = null,
        profileVisibility: String? = null
    ) {
        if (isPrivate == null && profileVisibility == null) return

        var normalizedUserId = normalizeUserId(userId)
        var cached = getLocalProfileRaw()

        privacyPrefs.edit().apply {
            isPrivate?.let { putBoolean(privateAccountKey(normalizedUserId), it) }
            profileVisibility?.let { putString(profileVisibilityKey(normalizedUserId), it.trim().lowercase()) }
        }.apply()

        if (cached != null && normalizeUserId(cached.id) == normalizedUserId) {
            saveProfile(
                cached.copy(
                    is_private = isPrivate ?: cached.is_private,
                    profile_visibility = profileVisibility?.trim()?.lowercase()
                        ?.takeIf { it.isNotBlank() }
                        ?: cached.profile_visibility
                )
            )
        }
    }

    fun applyLocalUserOverrides(user: User): User {
        return applyLocalProfileVisibility(
            applyLocalPresenceVisibility(
                applyLocalPrivacyOverride(user)
            )
        )
    }

    fun saveProfile(user: User) {
        try {
            var json = cacheJson.encodeToString(User.serializer(), user)
            profilePrefs.edit().putString(USER_CACHE_KEY, json)
                .putString(AVATAR_OWNER_KEY, normalizeUserId(user.id))
                .putString(AVATAR_URL_KEY, user.profileImage)
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("UserLocalStateStore", "Failed to cache profile", e)
        }
    }

    /** Compact first-frame identity without constructing repositories or waiting for the feed. */
    fun cachedAvatarForUser(userId: String?): String? {
        val owner = userId?.let(::normalizeUserId)?.takeIf(String::isNotBlank) ?: return null
        if (profilePrefs.getString(AVATAR_OWNER_KEY, null) == owner) {
            return profilePrefs.getString(AVATAR_URL_KEY, null)?.takeIf(String::isNotBlank)
        }
        // Older installations contain only the full profile. Never reuse another account's photo.
        return getLocalProfileRaw()?.takeIf { normalizeUserId(it.id) == owner }
            ?.profileImage?.takeIf(String::isNotBlank)
    }

    fun getLocalProfileRaw(): User? {
        return try {
            var json = profilePrefs.getString(USER_CACHE_KEY, null)
            if (json != null) {
                cacheJson.decodeFromString(User.serializer(), json)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getLocalProfile(): User? {
        val user = getLocalProfileRaw() ?: return null
        return applyLocalUserOverrides(user)
    }

    fun clearAll() {
        profilePrefs.edit().clear().apply()
        privacyPrefs.edit().clear().apply()
    }

    private fun applyLocalPrivacyOverride(user: User): User {
        return when {
            user.privacy_policy_accepted -> {
                val acceptedAt = user.privacy_policy_accepted_at
                    ?: java.time.OffsetDateTime.now().toString()
                setLocalPrivacyAccepted(user.id, acceptedAt, pendingSync = false)
                user
            }
            getLocalPrivacyAccepted(user.id) -> {
                user.copy(
                    privacy_policy_accepted = true,
                    privacy_policy_accepted_at = getLocalPrivacyAcceptedAt(user.id)
                        ?: user.privacy_policy_accepted_at
                )
            }
            else -> user
        }
    }

    private fun applyLocalPresenceVisibility(user: User): User {
        var localLastSeenVisibility = getLocalLastSeenVisibility(user.id)
        var localOnlineStatusVisibility = getLocalOnlineStatusVisibility(user.id)
        if (localLastSeenVisibility == null && localOnlineStatusVisibility == null) {
            return user
        }

        return user.copy(
            last_seen_visibility = localLastSeenVisibility ?: normalizePresenceVisibility(user.last_seen_visibility),
            online_status_visibility = localOnlineStatusVisibility ?: normalizePresenceVisibility(user.online_status_visibility)
        )
    }

    private fun applyLocalProfileVisibility(user: User): User {
        var localPrivate = getLocalPrivateAccount(user.id)
        var localProfileVisibility = getLocalProfileVisibility(user.id)
        if (localPrivate == null && localProfileVisibility == null) {
            return user
        }

        return user.copy(
            is_private = localPrivate ?: user.is_private,
            profile_visibility = localProfileVisibility ?: user.profile_visibility
        )
    }

    private fun getLocalLastSeenVisibility(userId: String): String? {
        return privacyPrefs.getString(lastSeenVisibilityKey(userId), null)
            ?.let(::normalizePresenceVisibility)
    }

    private fun getLocalOnlineStatusVisibility(userId: String): String? {
        return privacyPrefs.getString(onlineStatusVisibilityKey(userId), null)
            ?.let(::normalizePresenceVisibility)
    }

    private fun getLocalPrivateAccount(userId: String): Boolean? {
        val key = privateAccountKey(userId)
        if (!privacyPrefs.contains(key)) return null
        return privacyPrefs.getBoolean(key, false)
    }

    private fun getLocalProfileVisibility(userId: String): String? {
        return privacyPrefs.getString(profileVisibilityKey(userId), null)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
    }

    private fun privacyAcceptedKey(userId: String) =
        "privacy_policy_accepted_${normalizeUserId(userId)}"

    private fun privacyAcceptedAtKey(userId: String) =
        "privacy_policy_accepted_at_${normalizeUserId(userId)}"

    private fun privacyPendingKey(userId: String) =
        "privacy_policy_pending_${normalizeUserId(userId)}"

    private fun lastSeenVisibilityKey(userId: String) =
        "last_seen_visibility_${normalizeUserId(userId)}"

    private fun onlineStatusVisibilityKey(userId: String) =
        "online_status_visibility_${normalizeUserId(userId)}"

    private fun privateAccountKey(userId: String) =
        "private_account_${normalizeUserId(userId)}"

    private fun profileVisibilityKey(userId: String) =
        "profile_visibility_${normalizeUserId(userId)}"
}



