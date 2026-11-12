package conectate.com.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            TestRuntimeFixture.begin()
            harness = TestRuntimeFixture.seedMultiAccountMessagesHarness()
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
    fun multiAccountCenter_switchesMessagesContextWithoutLeavingInbox() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Activa: ${harness.activeAccount.display_name}").assertExists()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithContentDescription("Cambiar cuenta").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithText("Centro multi-cuenta").assertExists()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithText(harness.secondaryAccount.display_name.orEmpty()).assertIsDisplayed()
        composeRule.onNodeWithText("1 conversacion sin revisar").assertIsDisplayed()
        val auth = composeRule.activity.authRepository
        val firstVisit = auth.captureAccountLease()
        composeRule.onNodeWithText("Entrar a esta cuenta").performClick()

        // Wait for both the observable session and the completed operation before
        // checking the destination or starting another switch.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            !auth.accountSwitchUiState.value.isSwitching &&
                auth.getAuthStateFlow().value?.user?.id == harness.secondaryAccount.user_id
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Activa: ${harness.secondaryAccount.display_name}").assertExists()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithText("Bandeja de entrada").assertIsDisplayed()
        composeRule.onNodeWithText("Activa: ${harness.secondaryAccount.display_name}").assertIsDisplayed()
        composeRule.onNodeWithText("Centro multi-cuenta").assertDoesNotExist()
        assertFalse(firstVisit.isCurrent())

        val secondVisit = auth.captureAccountLease()
        composeRule.onNodeWithContentDescription("Cambiar cuenta").performClick()
        composeRule.onNodeWithText("Entrar a esta cuenta").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            !auth.accountSwitchUiState.value.isSwitching &&
                auth.getAuthStateFlow().value?.user?.id == harness.activeAccount.user_id
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Activa: ${harness.activeAccount.display_name}").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Centro multi-cuenta").assertDoesNotExist()
        composeRule.onNodeWithText("Bandeja de entrada").assertIsDisplayed()
        assertEquals(harness.activeAccount.user_id, auth.getAuthStateFlow().value?.user?.id)
        assertFalse(secondVisit.isCurrent())
        assertFalse(firstVisit.isCurrent())
    }
}
