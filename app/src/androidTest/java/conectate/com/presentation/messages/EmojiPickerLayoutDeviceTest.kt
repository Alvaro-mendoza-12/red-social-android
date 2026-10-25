package conectate.com.presentation.messages

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.presentation.messages.stickers.ChatEmojiGrid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmojiPickerLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enlargedSystemTextKeepsACompleteEmojiCellAndReturnsUnicodeText() {
        val selected = mutableStateOf("")
        compose.setContent {
            val density = LocalDensity.current.density
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                    ChatEmojiGrid(
                        query = "sonrisa feliz",
                        modifier = Modifier.width(320.dp).height(180.dp),
                        onEmojiSelected = { selected.value = it },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("sonrisa feliz")
            .assertHeightIsAtLeast(90.dp)
            .assertWidthIsAtLeast(90.dp)
            .performClick()
        compose.runOnIdle { assertEquals("😀", selected.value) }
    }
}
