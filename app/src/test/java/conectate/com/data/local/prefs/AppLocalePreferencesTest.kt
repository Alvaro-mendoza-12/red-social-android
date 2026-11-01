package conectate.com.data.local.prefs

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLocalePreferencesTest {
    private lateinit var context: Context
    private lateinit var previousDefault: Locale
    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        previousDefault = Locale.getDefault()
        context.getSharedPreferences("app_settings_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) manager().applicationLocales = LocaleList.getEmptyLocaleList()
    }
    @After fun restoreDefault() { Locale.setDefault(previousDefault) }
    private fun manager() = context.getSystemService(LocaleManager::class.java)
    private fun legacyLanguage(tag: String) {
        context.getSharedPreferences("app_settings_preferences", Context.MODE_PRIVATE).edit().putString("app_language", tag).commit()
    }

    @Test fun platformLanguageOverridesAnOlderPrivatePreference() {
        legacyLanguage("es")
        manager().applicationLocales = LocaleList.forLanguageTags("fr")
        assertEquals("fr", AppLocalePreferences.selected(context))
        assertEquals("fr", manager().applicationLocales.toLanguageTags())
    }

    @Test fun anExistingChoiceMigratesOnceAndAPlatformResetIsRespected() {
        legacyLanguage("es")
        AppLocalePreferences.wrap(context)
        assertEquals("es", manager().applicationLocales.toLanguageTags())
        manager().applicationLocales = LocaleList.getEmptyLocaleList()
        AppLocalePreferences.wrap(context)
        assertEquals(AppLocalePreferences.SYSTEM, AppLocalePreferences.selected(context))
        assertTrue(manager().applicationLocales.isEmpty)
    }

    @Test @Config(sdk = [28]) fun returningToSystemRestoresDefaultFormattingLocale() {
        val systemLocale = Locale.forLanguageTag("en-US")
        val base = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(systemLocale) })
        legacyLanguage("es")
        assertEquals("es", AppLocalePreferences.wrap(base).resources.configuration.locales[0].language)
        assertEquals("es", Locale.getDefault().language)
        legacyLanguage(AppLocalePreferences.SYSTEM)
        assertEquals(systemLocale, AppLocalePreferences.wrap(base).resources.configuration.locales[0])
        assertEquals(systemLocale, Locale.getDefault())
    }
}
