package conectate.com.presentation.social

import org.junit.Assert.*
import org.junit.Test

class HighlightPlaybackSessionTest {
    @Test fun `video end advances only once`() {
        val session = HighlightPlaybackSession("first", 0, 3)
        assertEquals(1, session.finish("first", 0, false, true))
        assertTrue(session.completed)
        assertNull(session.finish("first", 0, false, true))
    }

    @Test fun `last video or image returns close sentinel`() {
        val session = HighlightPlaybackSession("last", 2, 3)
        assertEquals(3, session.finish("last", 2, false, true))
    }

    @Test fun `previous page and other items cannot advance the current page`() {
        val session = HighlightPlaybackSession("first", 0, 3)
        assertNull(session.finish("second", 0, false, true))
        assertNull(session.finish("first", 1, false, true))
        assertFalse(session.completed)
    }

    @Test fun `dragging paused and background callbacks do not advance`() {
        val session = HighlightPlaybackSession("first", 0, 2)
        assertNull(session.finish("first", 0, true, true))
        assertNull(session.finish("first", 0, false, false))
        assertFalse(session.completed)
        assertEquals(1, session.finish("first", 0, false, true))
    }

    @Test fun `revisiting a completed page permits one new playback`() {
        val old = HighlightPlaybackSession("first", 0, 2)
        old.finish("first", 0, false, true)
        val visit = HighlightPlaybackSession("first", 0, 2)
        assertEquals(1, visit.finish("first", 0, false, true))
        assertNull(old.finish("first", 0, false, true))
    }

    @Test fun `delayed callback from an unfinished previous visit cannot finish a new visit to same page`() {
        val old = HighlightPlaybackSession("first", 0, 2)
        val visit = HighlightPlaybackSession("first", 0, 2)
        assertNull(old.finish("first", 0, false, true, currentSession = visit))
        assertFalse(visit.completed)
        assertEquals(1, visit.finish("first", 0, false, true, currentSession = visit))
    }
}
