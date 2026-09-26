package conectate.com.presentation.social

import org.junit.Assert.*
import org.junit.Test

class MomentMusicTrimPolicyTest {
    @Test fun `fifteen second video can use any fifteen seconds of a four minute song`() {
        val policy = MomentMusicTrimPolicy(240_000L, 15_000L)
        assertEquals(240_000L, policy.sourceDurationMs)
        assertEquals(15_000L, policy.maxClipMs)
        assertEquals(0f..15_000f, policy.initialRange(15_000L))
        assertEquals(120_000f..135_000f, policy.moveStart(120_000f, 0f..15_000f))
        assertEquals(225_000f..240_000f, policy.moveStart(240_000f, 0f..15_000f))
    }

    @Test fun `reopening or changing video length keeps the chosen late song offset`() {
        val policy = MomentMusicTrimPolicy(270_000L, 15_000L)
        assertEquals(240_000f..255_000f, policy.normalize(240_000f..255_000f))
        assertEquals(240_000f..255_000f, policy.normalize(240_000f..270_000f))
    }

    @Test fun `duration handle does not exceed video length or reduce below minimum`() {
        val policy = MomentMusicTrimPolicy(270_000L, 15_000L)
        val previous = 120_000f..130_000f
        assertEquals(120_000f..135_000f, policy.resize(120_000f..270_000f, previous))
        assertEquals(120_000f..125_000f, policy.resize(120_000f..120_000f, previous))
        assertEquals(115_000f..130_000f, policy.resize(0f..130_000f, previous))
        assertEquals(125_000f..130_000f, policy.resize(130_000f..130_000f, previous))
    }

    @Test fun `photos retain six second default but can select a chorus after four minutes`() {
        val policy = MomentMusicTrimPolicy(270_000L, null)
        assertEquals(0f..6_000f, policy.initialRange(null))
        assertEquals(240_000f..246_000f, policy.moveStart(240_000f, policy.initialRange(null)))
    }

    @Test fun `short source and short video have usable bounded ranges`() {
        val policy = MomentMusicTrimPolicy(3_000L, 15_000L)
        assertEquals(0f..3_000f, policy.initialRange(15_000L))
        assertEquals(0f..3_000f, policy.moveStart(99_000f, 0f..3_000f))
        assertEquals(1_500L, policy.fadeLimitMs(0f..3_000f))
        assertEquals(2_000L, MomentMusicTrimPolicy(270_000L, 2_000L).maxClipMs)
    }

    @Test fun `late selection retains sixty second moment limit without limiting song timeline`() {
        val policy = MomentMusicTrimPolicy(270_000L, 90_000L)
        assertEquals(60_000L, policy.maxClipMs)
        assertEquals(210_000f..270_000f, policy.moveStart(210_000f, 0f..60_000f))
        assertEquals(5_000L, policy.fadeLimitMs(210_000f..270_000f))
    }

    @Test fun `malformed ranges normalize and fade shrinks after recropping`() {
        val policy = MomentMusicTrimPolicy(270_000L, 15_000L)
        assertEquals(0f..5_000f, policy.normalize(Float.NaN..Float.NaN))
        assertEquals(265_000f..270_000f, policy.normalize(270_000f..270_000f))
        assertEquals(2_500L, policy.fadeLimitMs(120_000f..125_000f))
    }

    @Test fun `dragged selections stay valid throughout the whole song`() {
        for (content in listOf(2_000L, 15_000L, 60_000L)) {
            val policy = MomentMusicTrimPolicy(270_000L, content)
            for (start in -20_000L..300_000L step 997L) {
                val range = policy.moveStart(start.toFloat(), policy.initialRange(content))
                assertTrue(range.start >= 0f)
                assertTrue(range.endInclusive <= 270_000f)
                assertEquals(content.toFloat(), range.endInclusive - range.start, 0.1f)
            }
        }
    }
}
