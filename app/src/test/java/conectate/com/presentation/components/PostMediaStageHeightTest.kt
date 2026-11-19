package conectate.com.presentation.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PostMediaStageHeightTest {
    @Test fun photoUsesAvailableWidthBeforeTheImageLoads() {
        assertEquals(210.dp, postMediaStageHeight("portrait.jpg", 280.dp, 500.dp))
        assertEquals(300.dp, postMediaStageHeight("panorama.webp", 400.dp, 720.dp))
    }

    @Test fun photoRespectsHeightBoundsInNarrowAndWideWindows() {
        assertEquals(120.dp, postMediaStageHeight("small.png", 100.dp, 600.dp))
        assertEquals(360.dp, postMediaStageHeight("large.jpg", 800.dp, 500.dp))
        assertEquals(760.dp, postMediaStageHeight("large.jpg", 1600.dp, 1600.dp))
    }

    @Test fun videoCoverKeepsItsPlayerHeightWithinTheViewportBudget() {
        assertEquals(300.dp, postMediaStageHeight("cover.mp4?token=123", 400.dp, 600.dp))
        assertEquals(160.dp, postMediaStageHeight("cover.M3U8", 400.dp, 200.dp))
    }
}
