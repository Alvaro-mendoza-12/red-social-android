package conectate.com.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import conectate.com.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

@RunWith(AndroidJUnit4::class)
@LargeTest
class MessagesMultiAccountSwitchingTest {

    private lateinit var harness: SeededMultiAccountMessagesHarness

    private val seedRule = object : ExternalResource() {
        override fun before() {
            TestRuntimeFixture.clear()
            harness = TestRuntimeFixture.seedMultiAccountMessagesHarness()
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
    fun multiAccountCenter_switchesMessagesContextWithoutLeavingInbox() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Activa: ${harness.activeAccount.display_name}").assertExists()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithContentDescription("Centro multi-cuenta").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithText("Centro multi-cuenta").assertExists()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithText(harness.secondaryAccount.display_name.orEmpty()).assertIsDisplayed()
        composeRule.onNodeWithText("1 conversacion sin revisar").assertIsDisplayed()
        composeRule.onNodeWithText("Entrar a esta cuenta").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Activa: ${harness.secondaryAccount.display_name}").assertExists()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithContentDescription("Ajustes de mensajes").assertIsDisplayed()
        composeRule.onNodeWithText("Activa: ${harness.secondaryAccount.display_name}").assertIsDisplayed()
    }
}
