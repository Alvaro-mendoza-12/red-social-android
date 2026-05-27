package conectate.com.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class MessagesPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "messages_preferences"
        private const val KEY_COMPACT_MODE = "compact_mode"
        private const val KEY_CHAT_SOUNDS = "chat_sounds_enabled"
        private const val KEY_TYPING_INDICATORS = "typing_indicators_enabled"
        private const val KEY_CROSS_ACCOUNT_SUGGESTIONS = "cross_account_suggestions_enabled"
        private const val KEY_CONNECTIVITY_HINTS = "connectivity_hints_enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _compactMode = MutableStateFlow(prefs.getBoolean(KEY_COMPACT_MODE, false))
    val compactMode: StateFlow<Boolean> = _compactMode

    private val _chatSoundsEnabled = MutableStateFlow(prefs.getBoolean(KEY_CHAT_SOUNDS, true))
    val chatSoundsEnabled: StateFlow<Boolean> = _chatSoundsEnabled

    private val _typingIndicatorsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_TYPING_INDICATORS, true)
    )
    val typingIndicatorsEnabled: StateFlow<Boolean> = _typingIndicatorsEnabled

    private val _crossAccountSuggestionsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CROSS_ACCOUNT_SUGGESTIONS, true)
    )
    val crossAccountSuggestionsEnabled: StateFlow<Boolean> = _crossAccountSuggestionsEnabled

    private val _connectivityHintsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CONNECTIVITY_HINTS, true)
    )
    val connectivityHintsEnabled: StateFlow<Boolean> = _connectivityHintsEnabled

    fun setCompactMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPACT_MODE, enabled).apply()
        _compactMode.value = enabled
    }

    fun setChatSoundsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHAT_SOUNDS, enabled).apply()
        _chatSoundsEnabled.value = enabled
    }

    fun setTypingIndicatorsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TYPING_INDICATORS, enabled).apply()
        _typingIndicatorsEnabled.value = enabled
    }

    fun setCrossAccountSuggestionsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CROSS_ACCOUNT_SUGGESTIONS, enabled).apply()
        _crossAccountSuggestionsEnabled.value = enabled
    }

    fun setConnectivityHintsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONNECTIVITY_HINTS, enabled).apply()
        _connectivityHintsEnabled.value = enabled
    }
}


