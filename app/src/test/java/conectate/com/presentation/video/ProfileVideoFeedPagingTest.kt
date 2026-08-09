package conectate.com.presentation.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileVideoFeedPagingTest {

    @Test
    fun initialPageContainsTheVideoSelectedFromAnyProfileGridPosition() {
        listOf(0, 1, 23, 24, 25, 48, 99).forEach { selectedIndex ->
            val request = ProfileVideoFeedPaging.initialPage(selectedIndex, pageSize = 24)

            assertTrue(selectedIndex in request.offset until (request.offset + request.limit))
        }
    }

    @Test
    fun previousPageLoadsOnlyTheMissingPrefix() {
        assertEquals(PageRequest(offset = 0, limit = 13), ProfileVideoFeedPaging.previousPage(13, pageSize = 24))
        assertEquals(PageRequest(offset = 24, limit = 24), ProfileVideoFeedPaging.previousPage(48, pageSize = 24))
        assertNull(ProfileVideoFeedPaging.previousPage(0, pageSize = 24))
    }

    @Test
    fun invalidInputsAreSanitizedBeforeTheyReachTheRepository() {
        assertEquals(PageRequest(offset = 0, limit = 1), ProfileVideoFeedPaging.initialPage(-2, pageSize = 0))
    }

    @Test
    fun residentWindowKeepsOnlyThreePagesAroundNavigationDirection() {
        val items = (0 until 96).toList()

        val movingForward = ProfileVideoFeedPaging.boundedWindow(items, pageSize = 24, dropFromStart = true)
        assertEquals((24 until 96).toList(), movingForward.items)
        assertEquals(24, movingForward.removedFromStart)
        assertEquals(0, movingForward.removedFromEnd)

        val movingBackward = ProfileVideoFeedPaging.boundedWindow(items, pageSize = 24, dropFromStart = false)
        assertEquals((0 until 72).toList(), movingBackward.items)
        assertEquals(0, movingBackward.removedFromStart)
        assertEquals(24, movingBackward.removedFromEnd)
    }
}
