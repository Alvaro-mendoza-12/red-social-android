package conectate.com.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

@RunWith(AndroidJUnit4::class)
@LargeTest
class SecondarySavedAccountLoginFlowTest {

    private lateinit var harness: SeededSavedAccountsHarness

    private val seedRule = object : ExternalResource() {
        override fun before() {
            TestRuntimeFixture.begin()
            harness = TestRuntimeFixture.seedDualSavedAccountsLoginHarness()
        }

        override fun after() {
            TestRuntimeFixture.clear()
        }
    }

    private val composeRule = createEntryComposeRule()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(seedRule)
        .around(composeRule)

    @Test
    fun login_withSecondarySavedAccountFromLoginScreen_returnsToMessagesWithoutError() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Cuentas guardadas").assertExists()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithText("Cuentas guardadas").assertIsDisplayed()
        composeRule.onNodeWithText(harness.secondaryAccount.email).assertIsDisplayed()
        composeRule.onAllNodesWithText("Entrar")[1].performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Activa: ${harness.secondaryAccount.display_name}").assertExists()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithText("Activa: ${harness.secondaryAccount.display_name}").assertIsDisplayed()
    }
}
