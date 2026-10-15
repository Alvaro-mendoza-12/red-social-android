package conectate.com.presentation.video

import conectate.com.data.model.*
import org.junit.Assert.*
import org.junit.Test

class VideoFeedEntriesTest {
    private fun videos(n:Int)=(1..n).map { Video(id="v$it") }
    private val post=Post(id="p",userId="u",userName="User")
    private fun build(n:Int,tail:Boolean=false)=mixedVideoFeed(videos(n),listOf(LiveStream(id="live")),true,listOf(post),listOf(User(id="u")),
        listOf(SocializationCard("card","profile","User",reasonCode="related",action=SocializationCardAction("OPEN_PROFILE","u"))),tail)
    @Test fun emptyCatalogDoesNotBecomeAnAuxiliaryFeed() { assertTrue(build(0,true).isEmpty()) }
    @Test fun twoVideosHaveNoTerminalRecommendationRails() {
        val result=build(2,true);assertEquals(listOf("video:v1","video:v2","tail:recommended"),result.map { it.stableKey })
    }
    @Test fun longCatalogKeepsAllVideosInOrderAndUniqueKeys() {
        val result=build(80,true);assertEquals(videos(80),result.filterIsInstance<VideoFeedVideoEntry>().map { it.video })
        assertEquals(result.size,result.map { it.stableKey }.toSet().size);assertEquals(1,result.filterIsInstance<VideoFeedLiveEntry>().size)
        assertEquals(VideoFeedTailEntry,result.last())
    }
    @Test fun auxiliaryCardsHaveVideosOnBothSidesAndNeverTouchEachOther() {
        val result=build(40);var previous=-5
        result.forEachIndexed { i,item -> if(item !is VideoFeedVideoEntry) {
            assertTrue(result[i-1] is VideoFeedVideoEntry);assertTrue(result[i+1] is VideoFeedVideoEntry)
            assertTrue(result.subList(previous.coerceAtLeast(0)+1,i).count { it is VideoFeedVideoEntry }>=3);previous=i
        } }
    }
    @Test fun exactBoundaryDoesNotAppendASecondaryPageAtTheEnd() { assertTrue(build(8).all { it is VideoFeedVideoEntry }) }
    @Test fun duplicatePrimaryAndLiveIdsDoNotCreateDuplicatePagerKeys() {
        val result=mixedVideoFeed(videos(40)+Video(id="V1"),listOf(LiveStream(id="same"),LiveStream(id="SAME")),true)
        assertEquals(40,result.filterIsInstance<VideoFeedVideoEntry>().size);assertEquals(1,result.filterIsInstance<VideoFeedLiveEntry>().size)
        assertEquals(result.size,result.map { it.stableKey }.toSet().size)
    }
}
