package conectate.com.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SensitiveContentMode(val storageValue: String, val label: String) {
    HIDE("hide", "Ocultar"),
    BLUR("blur", "Difuminar"),
    SHOW("show", "Mostrar");

    companion object {
        fun fromStorage(raw: String?): SensitiveContentMode =
            entries.firstOrNull { it.storageValue == raw } ?: BLUR
    }
}

@Singleton
class ContentSafetyPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("content_safety_prefs", Context.MODE_PRIVATE)

    private val _sensitiveContentMode = MutableStateFlow(
        SensitiveContentMode.fromStorage(prefs.getString("sensitive_content_mode", null))
    )
    val sensitiveContentMode: StateFlow<SensitiveContentMode> = _sensitiveContentMode

    private val _adultConfirmed = MutableStateFlow(prefs.getBoolean("adult_confirmed", false))
    val adultConfirmed: StateFlow<Boolean> = _adultConfirmed

    private val _moderationWarningsEnabled = MutableStateFlow(
        prefs.getBoolean("moderation_warnings_enabled", true)
    )
    val moderationWarningsEnabled: StateFlow<Boolean> = _moderationWarningsEnabled

    fun setSensitiveContentMode(mode: SensitiveContentMode) {
        val safeMode = if (mode == SensitiveContentMode.SHOW && !_adultConfirmed.value) {
            SensitiveContentMode.BLUR
        } else {
            mode
        }
        prefs.edit().putString("sensitive_content_mode", safeMode.storageValue).apply()
        _sensitiveContentMode.value = safeMode
    }

    fun setAdultConfirmed(confirmed: Boolean) {
        prefs.edit().putBoolean("adult_confirmed", confirmed).apply()
        _adultConfirmed.value = confirmed
        if (!confirmed && _sensitiveContentMode.value == SensitiveContentMode.SHOW) {
            setSensitiveContentMode(SensitiveContentMode.BLUR)
        }
    }

    fun setModerationWarningsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("moderation_warnings_enabled", enabled).apply()
        _moderationWarningsEnabled.value = enabled
    }
}
