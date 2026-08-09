package conectate.com.data.local.prefs

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLocalePreferences {
    private const val PREFS_NAME = "app_settings_preferences"
    private const val KEY_LANGUAGE = "app_language"
    const val SYSTEM = "system"
    val supportedTags = setOf(SYSTEM, "es", "en", "pt")

    fun selected(context: Context): String = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_LANGUAGE, SYSTEM)
        ?.takeIf { it in supportedTags }
        ?: SYSTEM

    fun wrap(base: Context): ContextWrapper {
        val tag = selected(base)
        if (tag == SYSTEM) return ContextWrapper(base)
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return ContextWrapper(base.createConfigurationContext(configuration))
    }

    fun apply(activity: Activity, tag: String) {
        val normalized = tag.takeIf { it in supportedTags } ?: SYSTEM
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, normalized)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (normalized == SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(normalized)
            }
            activity.getSystemService(LocaleManager::class.java).applicationLocales = locales
        } else {
            activity.recreate()
        }
    }
}
