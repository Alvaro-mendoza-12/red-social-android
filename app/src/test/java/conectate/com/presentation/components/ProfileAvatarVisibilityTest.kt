package conectate.com.presentation.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class ProfileAvatarVisibilityTest {
    private val viewport = IntSize(400, 800)
    @Test fun clippedOrOffscreenAvatarsCannotStartTheirVideoPlayer() {
        listOf(Rect(401f, 20f, 449f, 68f), Rect(-49f, 20f, -1f, 68f),
            Rect(20f, 801f, 68f, 849f), Rect(20f, -49f, 68f, -1f),
            Rect(0f, 0f, 0f, 0f)).forEach { assertFalse(it.toString(), profileAvatarIsVisible(it, viewport)) }
    }
    @Test fun partiallyVisibleAvatarsStillAnimate() {
        assertTrue(profileAvatarIsVisible(Rect(-20f, 20f, 28f, 68f), viewport))
        assertTrue(profileAvatarIsVisible(Rect(390f, 790f, 438f, 838f), viewport))
    }
}
