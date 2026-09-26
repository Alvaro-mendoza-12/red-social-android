package conectate.com.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoChapterPolicyTest {
    @Test fun `chapter times accept seconds and minutes with seconds`() {
        assertEquals(12_000L, parseVideoChapterTime("12"))
        assertEquals(72_000L, parseVideoChapterTime("1:12"))
        assertEquals("1:12", videoChapterTimeLabel(72_500L))
        assertNull(parseVideoChapterTime("1:60"))
        assertNull(parseVideoChapterTime("1:2:3"))
        assertNull(parseVideoChapterTime("-1"))
        assertNull(parseVideoChapterTime("9223372036854775807:01"))
    }

    @Test fun `chapter list is ordered unique bounded and inside exact duration`() {
        assertTrue(validVideoChapters(listOf(
            VideoChapter(0, "Inicio"), VideoChapter(12_000, "Paso siguiente")
        ), 13_000))
        assertFalse(validVideoChapters(listOf(VideoChapter(13_000, "Fuera")), 13_000))
        assertFalse(validVideoChapters(listOf(VideoChapter(1_000, "A"), VideoChapter(1_000, "B")), 13_000))
        assertFalse(validVideoChapters(listOf(VideoChapter(2_000, "A"), VideoChapter(1_000, "B")), 13_000))
        assertFalse(validVideoChapters(listOf(VideoChapter(0, "Línea\nnueva")), 13_000))
        assertFalse(validVideoChapters((0L..50L).map { VideoChapter(it, "Capítulo") }, 13_000))
    }
}
