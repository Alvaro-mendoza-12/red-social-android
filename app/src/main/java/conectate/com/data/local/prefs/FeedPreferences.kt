package conectate.com.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SocialFeedMode(val storageValue: String, val apiValue: String) {
    DISCOVER("discover", "discover"),
    FOLLOWING("following", "following");

    companion object {
        fun fromStorage(value: String?): SocialFeedMode =
            entries.firstOrNull { it.storageValue == value } ?: DISCOVER
    }
}

@Singleton
class FeedPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences("social_feed_preferences", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        SocialFeedMode.fromStorage(preferences.getString(KEY_MODE, null))
    )
    val mode: StateFlow<SocialFeedMode> = _mode
    private val _mutedCreatorIds = MutableStateFlow(
        preferences.getStringSet(KEY_MUTED_CREATORS, emptySet()).orEmpty().toSet()
    )
    val mutedCreatorIds: StateFlow<Set<String>> = _mutedCreatorIds
    private val _mutedTerms = MutableStateFlow(
        preferences.getStringSet(KEY_MUTED_TERMS, emptySet()).orEmpty()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    )
    val mutedTerms: StateFlow<Set<String>> = _mutedTerms

    fun setMode(mode: SocialFeedMode) {
        preferences.edit().putString(KEY_MODE, mode.storageValue).apply()
        _mode.value = mode
    }

    fun muteCreator(userId: String) {
        val cleanId = userId.trim().lowercase()
        if (cleanId.isBlank()) return
        val updated = _mutedCreatorIds.value + cleanId
        preferences.edit().putStringSet(KEY_MUTED_CREATORS, updated).apply()
        _mutedCreatorIds.value = updated
    }

    fun clearMutedCreators() {
        preferences.edit().remove(KEY_MUTED_CREATORS).apply()
        _mutedCreatorIds.value = emptySet()
    }

    fun muteTerm(term: String) {
        val normalized = term.trim().lowercase().take(60)
        if (normalized.length < 2) return
        val updated = _mutedTerms.value + normalized
        preferences.edit().putStringSet(KEY_MUTED_TERMS, updated).apply()
        _mutedTerms.value = updated
    }

    fun unmuteTerm(term: String) {
        val updated = _mutedTerms.value - term.trim().lowercase()
        preferences.edit().putStringSet(KEY_MUTED_TERMS, updated).apply()
        _mutedTerms.value = updated
    }

    private companion object {
        const val KEY_MODE = "default_feed_mode"
        const val KEY_MUTED_CREATORS = "muted_creator_ids"
        const val KEY_MUTED_TERMS = "muted_terms"
    }
}
