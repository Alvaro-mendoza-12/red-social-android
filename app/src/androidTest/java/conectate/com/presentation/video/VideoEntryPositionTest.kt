package conectate.com.presentation.video

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import conectate.com.data.model.Video
import org.junit.Rule
import org.junit.Test

/** Regression: the pager is hidden while the requested video's entry is loading. */
class VideoEntryPositionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedVideoCanOpenBeforePagerHasEverLaidOut() = checkEntry(initiallyLoaded = true)
    @Test fun searchedVideoCanOpenAfterItsDetailArrives() = checkEntry(initiallyLoaded = false)

    private fun checkEntry(initiallyLoaded: Boolean) {
        val pages = listOf("first", "second", "requested").map { VideoFeedVideoEntry(Video(id = it)) }
        val loaded = mutableStateOf(initiallyLoaded)
        compose.setContent {
            val entries = if (loaded.value) pages else emptyList()
            val pager = rememberPagerState(pageCount = { entries.size })
            var applied by remember { mutableStateOf(false) }
            PositionInitialVideoPage("REQUESTED", entries, pager, applied) { applied = true }
            if (!applied) Text("Abriendo video…")
            else VerticalPager(pager, Modifier.fillMaxSize()) { page -> Text("Video ${entries[page].video.id}") }
        }
        if (!initiallyLoaded) {
            compose.onNodeWithText("Abriendo video…").assertIsDisplayed()
            compose.runOnIdle { loaded.value = true }
        }
        compose.onNodeWithText("Video requested").assertIsDisplayed()
        compose.onNodeWithText("Abriendo video…").assertDoesNotExist()
    }
}
