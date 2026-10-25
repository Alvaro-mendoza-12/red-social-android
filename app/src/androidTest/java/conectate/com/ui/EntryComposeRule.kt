package conectate.com.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import conectate.com.MainActivity

/**
 * MainActivity consumes a delivered intent and replaces it with an explicit
 * ACTION_MAIN intent. Launch with the same filter so ActivityScenario continues
 * tracking its lifecycle after consumption, including close/recreation.
 */
internal fun createEntryComposeRule() = AndroidComposeTestRule(
    activityRule = ActivityScenarioRule<MainActivity>(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN),
    ),
    activityProvider = { rule ->
        var activity: MainActivity? = null
        rule.scenario.onActivity { activity = it }
        checkNotNull(activity)
    },
)
