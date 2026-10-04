package conectate.com.data.music

import org.junit.Assert.*
import org.junit.Test

class SyncedMusicLyricsTest {
    @Test fun `plain lyrics never invent synchronization`() {
        assertTrue(timedMusicLyrics("Original QA line A\nOriginal QA line B").isEmpty())
    }

    @Test fun `real timestamps retain milliseconds and source track positions`() {
        val cues = timedMusicLyrics("[01:02.3]QA A\n[01:03.45]QA B\n[01:04:567]QA C", 70_000)
        assertEquals(listOf(62_300L, 63_450L, 64_567L), cues.map { it.startMs })
        assertEquals(listOf(63_450L, 64_567L, 70_000L), cues.map { it.endMs })
        assertEquals(1, activeMusicLyricIndex(cues, 63_500))
        assertEquals(-1, activeMusicLyricIndex(cues, 3_500))
    }

    @Test fun `repeated timestamps and duplicate lines remain ordered`() {
        val cues = timedMusicLyrics("[00:03][00:01]QA A\n[00:01]QA A\n[00:02]QA B", 5_000)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), cues.map { it.startMs })
        assertEquals(listOf("QA A", "QA B", "QA A"), cues.map { it.text })
    }

    @Test fun `blank timed marker stops a line during an instrumental gap`() {
        val cues = timedMusicLyrics("[00:00]QA A\n[00:02]\n[00:04]QA B", 6_000)
        assertEquals(0, activeMusicLyricIndex(cues, 1_999))
        assertEquals(-1, activeMusicLyricIndex(cues, 2_000))
        assertEquals(2, activeMusicLyricIndex(cues, 4_000))
        assertEquals(-1, activeMusicLyricIndex(cues, 6_000))
    }

    @Test fun `offset is applied and bounded without negative positions`() {
        val cues = timedMusicLyrics("[offset:-250]\n[00:00.100]QA A\n[00:01]QA B", 3_000)
        assertEquals(listOf(0L, 750L), cues.map { it.startMs })
        assertTrue(timedMusicLyrics("[offset:999999]\n[00:01]QA A").isEmpty())
        assertTrue(timedMusicLyrics("[offset:999999999999999999999]\n[00:01]QA A").isEmpty())
    }

    @Test fun `malformed timestamps and metadata are ignored`() {
        val cues = timedMusicLyrics("[ar:QA artist]\n[00:70]wrong\n[999999:00]wrong\n[00:03.1234]wrong\n[00:01]QA A", 3_000)
        assertEquals(1, cues.size)
        assertEquals("QA A", cues.single().text)
    }

    @Test fun `bounds reject excessive data and truncate displayed line length`() {
        assertTrue(timedMusicLyrics("[00:00]" + "x".repeat(12_001)).isEmpty())
        assertEquals(280, timedMusicLyrics("[00:00]" + "x".repeat(400)).single().text.length)
    }

    @Test fun `track duration excludes cues outside the source and bounds its last line`() {
        val cues = timedMusicLyrics("[00:01]QA A\n[00:05]QA B", 5_000)
        assertEquals(1, cues.size)
        assertEquals(5_000L, cues.single().endMs)
        assertEquals(-1, activeMusicLyricIndex(cues, -1))
        assertEquals(-1, activeMusicLyricIndex(cues, 5_000))
    }
}
