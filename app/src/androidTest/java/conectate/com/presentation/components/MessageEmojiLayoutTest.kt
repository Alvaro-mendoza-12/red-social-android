package conectate.com.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageEmojiLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun wrappedLargeEmojiHaveEnoughVerticalSpaceForTheirGlyphs() {
        val measured = AtomicReference<Pair<TextLayoutResult, Float>>()
        compose.setContent {
            val minimumAdvance = with(LocalDensity.current) { 34.sp.toPx() }
            MaterialTheme {
                LinkText(
                    text = "😊🏐😋💙👻😝🕒\n🕒🥺😋🏐",
                    modifier = Modifier.width(220.dp),
                    fontSize = 34.sp,
                    onTextLayout = { measured.set(it to minimumAdvance) },
                )
            }
        }
        compose.waitUntil(10_000) { measured.get() != null }
        compose.runOnIdle {
            val (layout, minimum) = checkNotNull(measured.get())
            assertTrue("The emoji sample must wrap", layout.lineCount >= 2)
            for (line in 1 until layout.lineCount) {
                val advance = layout.getLineTop(line) - layout.getLineTop(line - 1)
                assertTrue("Emoji lines overlap: $advance < $minimum px", advance >= minimum)
            }
        }
    }
}
