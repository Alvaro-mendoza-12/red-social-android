package conectate.com.presentation.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReactionsOverlayTest {
    @Test
    fun `heart remains visible through most of its flight`() {
        assertEquals(1f, reactionMotion(0.55f, 7).alpha, 0.001f)
        assertTrue(reactionMotion(0.55f, 7).riseDp < -100f)
    }

    @Test
    fun `heart fades only near the end and stays within a small curve`() {
        val final = reactionMotion(1f, 19)
        assertEquals(0f, final.alpha, 0.001f)
        assertEquals(-220f, final.riseDp, 0.001f)
        assertTrue(kotlin.math.abs(final.lateralDp) <= 14f)
    }

    @Test
    fun `heart reaches the top area on a tall live screen`() {
        val nearTop = reactionMotion(progress = 0.94f, seed = 11, maxRiseDp = 760f)

        assertTrue(nearTop.riseDp < -740f)
        assertTrue(nearTop.alpha > 0f)
    }
}
