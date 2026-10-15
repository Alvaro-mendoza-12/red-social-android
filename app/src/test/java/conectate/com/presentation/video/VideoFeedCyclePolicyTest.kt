package conectate.com.presentation.video
import conectate.com.data.model.Video
import org.junit.Assert.*
import org.junit.Test
class VideoFeedCyclePolicyTest {
    @Test fun twoCachedVideosCannotTruncateTwelveServerItems() {
        val current=listOf("v1","v2").map { Video(id=it) };val remote=(1..12).map { Video(id="v$it") }
        val merged=mergeRankedPageAfterDisplayedPrefix(current,remote,setOf("v1","v2"));assertEquals(12,merged.size);assertEquals(12,merged.map { it.id }.toSet().size)
    }
    @Test fun smallSeenCatalogStillContinuesAndAvoidsLastVideo() {
        val page=listOf("one","two").map { Video(id=it) };val next=continuedVideoOrder(page,setOf("one","two"),"two")
        assertEquals(listOf("one","two"),next.map { it.id })
    }
    @Test fun newContentPrecedesPreviouslySeenContent() {
        val page=listOf("old","new","last").map { Video(id=it) };assertEquals("new",continuedVideoOrder(page,setOf("old","last"),"last").first().id)
    }
    @Test fun duplicateKeysAreNotIntroducedWithinACycle() {
        val page=listOf("old","OLD","last").map { Video(id=it) };assertEquals(2,continuedVideoOrder(page,emptySet(),"last").size)
    }
    @Test fun oneVideoCanBeRevisitedWithoutInventingOthers() {
        val page=listOf(Video(id="one"));assertEquals(page,continuedVideoOrder(page,setOf("one"),"one"))
    }

    @Test fun aConsumedTwelveItemCatalogDoesNotRestartTheSameOrder() {
        val page=(1..12).map { Video(id="v$it") }
        val next=continuedVideoOrder(page,page.map { it.id }.toSet(),"v12",page.map { it.id }.reversed(),"v1")
        assertNotEquals(page.map { it.id },next.map { it.id })
        assertNotEquals("v1",next.first().id)
        assertNotEquals("v12",next.first().id)
        assertEquals((1..6).map { "v$it" }.toSet(),next.take(6).map { it.id }.toSet())
        assertEquals(page.map { it.id }.toSet(),next.map { it.id }.toSet())
    }
}
