package conectate.com.presentation.video
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import conectate.com.data.model.Video
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
class VideoFeedCycleDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun reachingTailAfterTwoVideosStartsAnotherCycleAutomatically() {
        var calls=0
        compose.setContent {
            var session by remember { mutableStateOf("first") }
            var videos by remember { mutableStateOf(listOf(Video(id="one"),Video(id="two"))) }
            val entries=mixedVideoFeed(videos,emptyList(),false,includeTail=true)
            val pager=rememberPagerState { entries.size }
            LaunchedEffect(session) { pager.requestScrollToPage(0) }
            ContinueRecommendedFeedAtTail(pager,entries,session,false,null,true) {
                calls++;videos=(1..12).map { Video(id="new-$it") };session="second"
            }
            VerticalPager(pager,Modifier.fillMaxSize().testTag("pager")) { page ->
                val e=entries[page];if(e is VideoFeedVideoEntry) Text(e.video.id) else Text("Cargando más videos")
            }
        }
        compose.onNodeWithTag("pager").performTouchInput { swipeUp() };compose.waitForIdle()
        compose.onNodeWithTag("pager").performTouchInput { swipeUp() }
        compose.waitUntil(8000) { calls==1 };compose.waitForIdle();compose.onNodeWithText("new-1").assertIsDisplayed()
        compose.onNodeWithText("Llegaste al final").assertDoesNotExist()
    }
    @Test fun currentVideoDoesNotStartANewCycleBeforeTail() {
        var calls=0;compose.setContent {
            val entries=mixedVideoFeed(listOf(Video(id="one"),Video(id="two")),emptyList(),false,includeTail=true)
            val pager=rememberPagerState { entries.size }
            ContinueRecommendedFeedAtTail(pager,entries,"first",false,null,true) { calls++ }
            VerticalPager(pager,Modifier.fillMaxSize()) { page -> Text((entries[page] as? VideoFeedVideoEntry)?.video?.id?:"tail") }
        };compose.waitForIdle();assertEquals(0,calls);compose.onNodeWithText("one").assertIsDisplayed()
    }
    @Test fun failedContinuationDoesNotAutomaticallySpinRequests() {
        var calls=0;compose.setContent {
            val entries=listOf(VideoFeedVideoEntry(Video(id="one")),VideoFeedTailEntry)
            val pager=rememberPagerState(initialPage=1) { entries.size }
            ContinueRecommendedFeedAtTail(pager,entries,"first",false,"Network error",true) { calls++ }
            Text("Reintentar")
        };compose.waitForIdle();assertEquals(0,calls)
    }
}
