package conectate.com.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import conectate.com.core.testing.TestRuntimeOverrides
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class TestRuntimeFixtureIsolationTest {
    @Test
    fun seededAccountFixturesRestoreAllPreferencesAfterTheTest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceNames = listOf(TestRuntimeOverrides.PREFS_NAME, "multi_account_inbox")
        val before = preferenceNames.associateWith { name ->
            copyPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
        }

        TestRuntimeFixture.begin()
        try {
            TestRuntimeFixture.seedMultiAccountMessagesHarness()
            assertEquals(true, context.getSharedPreferences(TestRuntimeOverrides.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(TestRuntimeOverrides.ENABLE_AUTH_FIXTURES_KEY, false))
        } finally {
            TestRuntimeFixture.clear()
        }

        preferenceNames.forEach { name ->
            assertEquals(before.getValue(name), context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
        }
    }

    private fun copyPreferences(values: Map<String, *>): Map<String, Any> = values.mapNotNull { (key, value) ->
        when (value) {
            is String, is Int, is Long, is Float, is Boolean -> key to value
            is Set<*> -> key to value.filterIsInstance<String>().toMutableSet()
            else -> null
        }
    }.toMap()
}
