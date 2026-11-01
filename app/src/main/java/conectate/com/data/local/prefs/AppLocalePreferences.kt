package conectate.com.data.local.prefs

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

object AppLocalePreferences {
    private const val PREFS_NAME = "app_settings_preferences"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_PLATFORM_LANGUAGE_MIGRATED = "app_language_platform_migrated"
    private val migrationLock = Any()
    const val SYSTEM = "system"
    val supportedTags = setOf(SYSTEM, "es", "en", "pt", "fr", "de", "ja", "zh-Hans", "zh-Hant")

    fun selected(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            migrateLegacyLanguage(context)
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return SYSTEM
            val locale = locales[0]
            val exact = supportedTags.firstOrNull { it.equals(locale.toLanguageTag(), ignoreCase = true) }
            if (exact != null) return exact
            return when (locale.language) {
                "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
                else -> locale.language.takeIf { it in supportedTags } ?: SYSTEM
            }
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM)?.takeIf { it in supportedTags } ?: SYSTEM
    }

    /** After upgrading Android, migrate once; a later system reset must stay reset. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun migrateLegacyLanguage(context: Context) = synchronized(migrationLock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PLATFORM_LANGUAGE_MIGRATED, false)) return@synchronized
        val manager = context.getSystemService(LocaleManager::class.java)
        val legacy = prefs.getString(KEY_LANGUAGE, SYSTEM)?.takeIf { it in supportedTags } ?: SYSTEM
        if (manager.applicationLocales.isEmpty && legacy != SYSTEM) {
            manager.applicationLocales = LocaleList.forLanguageTags(legacy)
        }
        prefs.edit().putBoolean(KEY_PLATFORM_LANGUAGE_MIGRATED, true).apply()
    }

    fun wrap(base: Context): ContextWrapper {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            migrateLegacyLanguage(base)
            // Android owns this configuration, including changes made in system Settings.
            return ContextWrapper(base)
        }
        val tag = selected(base)
        if (tag == SYSTEM) {
            Locale.setDefault(base.resources.configuration.locales[0])
            return ContextWrapper(base)
        }
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
            .putBoolean(KEY_PLATFORM_LANGUAGE_MIGRATED, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
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
