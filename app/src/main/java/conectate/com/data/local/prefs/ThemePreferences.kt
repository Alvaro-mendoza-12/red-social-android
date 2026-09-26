package conectate.com.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemePreferences @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    // Fresh installs start in dark mode; an existing explicit user choice is preserved.
    private val _isDarkMode = MutableStateFlow(prefs.getBoolean("is_dark_mode", true))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    private val _appTheme = MutableStateFlow(
        AppThemeOption.fromStorage(
            prefs.getString("app_theme", AppThemeOption.NEUTRO.storageValue)
        )
    )
    val appTheme: StateFlow<AppThemeOption> = _appTheme

    private val _profileAvatarVideosMuted = MutableStateFlow(
        prefs.getBoolean("profile_avatar_videos_muted", true)
    )
    val profileAvatarVideosMuted: StateFlow<Boolean> = _profileAvatarVideosMuted

    private val _fontOption = MutableStateFlow(
        AppFontOption.fromStorage(
            prefs.getString("app_font_option", AppFontOption.DEFAULT.storageValue)
        )
    )
    val fontOption: StateFlow<AppFontOption> = _fontOption

    private val _textScale = MutableStateFlow(
        prefs.getFloat("app_text_scale", 1f).coerceIn(0.9f, 1.25f)
    )
    val textScale: StateFlow<Float> = _textScale

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("is_dark_mode", enabled).apply()
        _isDarkMode.value = enabled
    }

    fun setAppTheme(theme: AppThemeOption) {
        prefs.edit().putString("app_theme", theme.storageValue).apply()
        _appTheme.value = theme
    }

    fun setProfileAvatarVideosMuted(enabled: Boolean) {
        prefs.edit().putBoolean("profile_avatar_videos_muted", enabled).apply()
        _profileAvatarVideosMuted.value = enabled
    }

    fun setFontOption(option: AppFontOption) {
        prefs.edit().putString("app_font_option", option.storageValue).apply()
        _fontOption.value = option
    }

    fun setTextScale(scale: Float) {
        val clamped = scale.coerceIn(0.9f, 1.25f)
        prefs.edit().putFloat("app_text_scale", clamped).apply()
        _textScale.value = clamped
    }
}


