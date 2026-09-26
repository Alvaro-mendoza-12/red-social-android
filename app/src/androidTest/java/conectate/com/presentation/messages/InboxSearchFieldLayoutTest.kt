package conectate.com.presentation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class InboxSearchFieldLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun narrowPhoneAndLargeTextKeepSearchLabelVisibleInsideTheField() {
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale = 1.4f)) {
                MaterialTheme {
                    Box(Modifier.width(390.dp)) {
                        InboxSearchField(
                            query = "",
                            onQueryChange = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Buscar conversaciones").assertIsDisplayed()
        val fieldBounds = composeRule.onNodeWithTag("inbox_conversation_search")
            .fetchSemanticsNode().boundsInRoot
        val expectedMinHeight = with(composeRule.density) { 56.dp.toPx() }
        assertTrue("The search field must not collapse to the previous 46 dp height", fieldBounds.height >= expectedMinHeight - 1f)
    }
}
