package conectate.com.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import conectate.com.MainActivity
import conectate.com.core.testing.RuntimeSessionOverride
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

@RunWith(AndroidJUnit4::class)
@LargeTest
class SavedAccountLogoutLoginFlowTest {

    private lateinit var session: RuntimeSessionOverride

    private val seedRule = object : ExternalResource() {
        override fun before() {
            TestRuntimeFixture.begin()
            session = TestRuntimeFixture.seedSavedAccountLoginHarness()
        }

        override fun after() {
            TestRuntimeFixture.clear()
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(seedRule)
        .around(composeRule)

    @Test
    fun login_withSavedAccountFromLoginScreen_returnsToMessages() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Cuentas guardadas").assertExists()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithText("Cuentas guardadas").assertIsDisplayed()
        composeRule.onNodeWithText(session.email).assertIsDisplayed()
        composeRule.onNodeWithText("Entrar").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Activa: ${session.displayName}").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Activa: ${session.displayName}").assertIsDisplayed()
    }
}
