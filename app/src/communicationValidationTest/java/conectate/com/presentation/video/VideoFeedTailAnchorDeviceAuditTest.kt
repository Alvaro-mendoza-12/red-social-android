package conectate.com.presentation.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.LiveStream
import conectate.com.data.model.Post
import conectate.com.data.model.Video
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VideoFeedTailAnchorDeviceAuditTest {
    @get:Rule val compose = createComposeRule()

    private val streams = listOf(LiveStream(id = "live"))
    private val posts = listOf(Post(id = "post", userId = "author", userName = "Author"))
    private fun entries(videos: List<Video>) = mixedVideoFeed(
        videos, streams, includeLives = true, socialPosts = posts, includeTail = true,
    )
    private fun focusedKey(pager: PagerState): Any? =
        pager.layoutInfo.visiblePagesInfo.firstOrNull { it.index == pager.currentPage }?.key

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "communication-qa/" + name)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun tailAdmitsTheFirstNewVideoAcrossNewSocialAndLiveRails() {
        val videos = mutableStateOf((1..8).map { Video(id = "v" + it) })
        lateinit var pager: PagerState
        compose.setContent {
            MaterialTheme {
                val mixedEntries = entries(videos.value)
                val state = rememberPagerState(pageCount = { mixedEntries.size })
                pager = state
                MaintainVideoFeedTailAppendAnchor(state, videos.value, mixedEntries, "session", true)
                VerticalPager(state = state, key = { mixedEntries[it].stableKey }, modifier = Modifier.fillMaxSize()) { page ->
                    val entry = mixedEntries[page]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(entry.stableKey, Modifier.testTag("page:" + entry.stableKey))
                    }
                }
            }
        }
        compose.runOnIdle { pager.requestScrollToPage(entries(videos.value).lastIndex) }
        compose.waitForIdle()
        compose.onNodeWithTag("page:tail:recommended").assertIsDisplayed()

        compose.runOnIdle { videos.value = (1..13).map { Video(id = "v" + it) } }
        compose.waitUntil(5_000) { focusedKey(pager) == "video:v9" }
        compose.waitForIdle()
        compose.onNodeWithTag("page:video:v9").assertIsDisplayed()
        compose.runOnIdle {
            val mixedEntries = entries(videos.value)
            assertEquals("social:post", mixedEntries[8].stableKey)
            assertEquals("video:v9", mixedEntries[9].stableKey)
            assertTrue(mixedEntries.any { it.stableKey == "live:live" })
            assertEquals(9, pager.currentPage)
        }
        capture("video-feed-tail-append-anchor.png")
    }

    @Test fun lateRankAndCountersPreserveTheCurrentlySelectedVideo() {
        val videos = mutableStateOf((1..8).map { Video(id = "v" + it) })
        lateinit var pager: PagerState
        compose.setContent {
            MaterialTheme {
                val mixedEntries = entries(videos.value)
                val state = rememberPagerState(pageCount = { mixedEntries.size })
                pager = state
                MaintainVideoFeedTailAppendAnchor(state, videos.value, mixedEntries, "session", true)
                VerticalPager(state = state, key = { mixedEntries[it].stableKey }, modifier = Modifier.fillMaxSize()) { page ->
                    val entry = mixedEntries[page]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(entry.stableKey, Modifier.testTag("page:" + entry.stableKey))
                    }
                }
            }
        }
        compose.runOnIdle { pager.requestScrollToPage(4) }
        compose.waitForIdle()
        compose.onNodeWithTag("page:video:v5").assertIsDisplayed()

        compose.runOnIdle {
            videos.value = listOf(9, 10, 8, 7, 6, 5, 4, 3, 2, 1).map { Video(id = "v" + it) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("page:video:v5").assertIsDisplayed()
        var selectedPage = -1
        compose.runOnIdle {
            assertEquals("video:v5", focusedKey(pager))
            selectedPage = pager.currentPage
            videos.value = videos.value.map { it.copy(views = it.views + 1) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("page:video:v5").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("video:v5", focusedKey(pager))
            assertEquals(selectedPage, pager.currentPage)
        }
        capture("video-feed-active-anchor-stable.png")
    }
}
