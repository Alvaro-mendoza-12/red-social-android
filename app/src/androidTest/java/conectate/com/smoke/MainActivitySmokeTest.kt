package conectate.com.smoke

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import conectate.com.MainActivity
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun isPresent(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    // Do not use the destructive fixture runner: this smoke also runs on an existing account.
    private fun withApp(block: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_runtime_prefs", Context.MODE_PRIVATE)
        val key = "disable_startup_prompts"
        val existed = prefs.contains(key)
        val previous = prefs.getBoolean(key, false)
        prefs.edit().putBoolean(key, true).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                composeRule.waitUntil(45_000) { isPresent("main-home") || isPresent("auth-login") }
                block()
            }
        } finally {
            prefs.edit().apply { if (existed) putBoolean(key, previous) else remove(key) }.commit()
        }
    }

    @Test
    fun coldLaunchReachesUsableEntryScreen() = withApp {
        val entry = if (isPresent("main-home")) "main-home" else "auth-login"
        composeRule.onNodeWithTag(entry).assertIsDisplayed()
        Log.i("AndroidSmoke", "Usable entry screen: $entry")
    }

    @Test
    fun existingAccountCanNavigateAllFiveSections() = withApp {
        assumeTrue("Requires an existing account; the test never creates one", isPresent("main-home"))
        listOf("messages", "videos", "social", "games", "profile", "messages").forEach { route ->
            val tab = composeRule.onNodeWithTag("main-tab-$route")
            tab.assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            tab.assertIsSelected()
            Log.i("AndroidSmoke", "Selected section: $route")
        }
    }
}
