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
        private const val KEY_ENTER_TO_SEND = "enter_to_send_enabled"
        private const val KEY_MEDIA_AUTO_DOWNLOAD = "media_auto_download_enabled"
        private const val KEY_READ_RECEIPTS = "read_receipts_enabled"
        private const val KEY_CHAT_ACCENT = "chat_accent"
        private const val KEY_CHAT_BACKGROUND = "chat_background"

        fun isMediaAutoDownloadEnabled(context: Context): Boolean = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEDIA_AUTO_DOWNLOAD, true)
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

    private val _enterToSendEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_ENTER_TO_SEND, false)
    )
    val enterToSendEnabled: StateFlow<Boolean> = _enterToSendEnabled

    private val _mediaAutoDownloadEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_MEDIA_AUTO_DOWNLOAD, true)
    )
    val mediaAutoDownloadEnabled: StateFlow<Boolean> = _mediaAutoDownloadEnabled

    private val _readReceiptsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_READ_RECEIPTS, true)
    )
    val readReceiptsEnabled: StateFlow<Boolean> = _readReceiptsEnabled

    private val _chatAccent = MutableStateFlow(
        prefs.getString(KEY_CHAT_ACCENT, "conectate_red") ?: "conectate_red"
    )
    val chatAccent: StateFlow<String> = _chatAccent

    private val _chatBackground = MutableStateFlow(
        prefs.getString(KEY_CHAT_BACKGROUND, "deep_black") ?: "deep_black"
    )
    val chatBackground: StateFlow<String> = _chatBackground

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

    fun setEnterToSendEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENTER_TO_SEND, enabled).apply()
        _enterToSendEnabled.value = enabled
    }

    fun setMediaAutoDownloadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_AUTO_DOWNLOAD, enabled).apply()
        _mediaAutoDownloadEnabled.value = enabled
    }

    fun setReadReceiptsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_READ_RECEIPTS, enabled).apply()
        _readReceiptsEnabled.value = enabled
    }

    fun setChatAccent(value: String) {
        prefs.edit().putString(KEY_CHAT_ACCENT, value).apply()
        _chatAccent.value = value
    }

    fun setChatBackground(value: String) {
        prefs.edit().putString(KEY_CHAT_BACKGROUND, value).apply()
        _chatBackground.value = value
    }
}


