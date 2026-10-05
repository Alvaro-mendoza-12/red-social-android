package conectate.com

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.music.ApprovedMomentMusic
import conectate.com.data.signaling.SignalingConfig
import conectate.com.presentation.social.MomentMusicPickerDialog
import conectate.com.presentation.social.MomentMusicSelection
import conectate.com.presentation.social.MusicCatalogUiState
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Plays an approved community stream through the actual video Moment selector. */
@RunWith(AndroidJUnit4::class)
class MomentMusicPickerServerAuditTest {
    @get:Rule val compose = createComposeRule()
    @Test(timeout = 90_000) fun communityTrackStartsOnFirstTapAndSelectionUsesTheOriginal() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditCommunityPicker") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText()).getJSONObject(0)
        val client = OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(20, TimeUnit.SECONDS).build()
        val response = client.newCall(Request.Builder()
            .url(SignalingConfig.BASE_URL + "/v1/data/music/catalog/search?section=community&limit=5")
            .header("Authorization", "Bearer ${fixture.getString("token")}").build()).execute().use {
            assertTrue("Community catalog request failed: ${it.code}", it.isSuccessful)
            JSONObject(it.body!!.string())
        }
        val items = response.getJSONArray("items")
        val json = Json { ignoreUnknownKeys = true }
        val tracks = (0 until items.length()).map { json.decodeFromString<ApprovedMomentMusic>(items.getJSONObject(it).toString()) }
        val track = tracks.first { it.source == "novvin_community" && it.durationMs >= 10_000 }
        val selection = AtomicReference<MomentMusicSelection?>()
        val shown = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (shown.value) MomentMusicPickerDialog(
                    MusicCatalogUiState(items = listOf(track), section = "community"),
                    contentDurationMs = 12_000, hasOriginalAudio = true, initialSelection = null,
                    onSearch = { _, _, _ -> }, onLoadMore = {}, onToggleFavorite = {},
                    onReport = {}, onNotInterested = {}, onPreviewStarted = {},
                    onOpenCommunityUpload = {}, onOpenDeviceMusic = {}, onDismiss = {}, onRemove = {},
                    onSelect = { selection.set(it); shown.value = false },
                )
            }
        }
        compose.onNodeWithContentDescription("Escuchar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pausar").assertDoesNotExist()
        val reviewDelay = InstrumentationRegistry.getArguments().getString("pickerReviewDelayMs")?.toLong() ?: 0L
        require(reviewDelay in 0..3_000)
        if (reviewDelay > 0) Thread.sleep(reviewDelay)
        val tappedAt = System.nanoTime()
        compose.onNodeWithContentDescription("Escuchar").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Pausar").fetchSemanticsNodes().isNotEmpty() }
        val playbackMs = (System.nanoTime() - tappedAt) / 1_000_000
        compose.onNodeWithContentDescription("Pausar").performClick()
        compose.onNodeWithText(track.title).performClick()
        compose.onNodeWithText("Usar audio").performClick()
        val selected = requireNotNull(selection.get())
        assertEquals(track.audioUrl, selected.track.audioUrl)
        assertEquals(track.id, selected.track.id)
        assertTrue(selected.endMs > selected.startMs)
        assertTrue(selected.endMs <= track.durationMs)
        assertTrue(selected.endMs - selected.startMs <= 12_000)
        val reportName = if (reviewDelay == 0L) "community-picker-first-play.json" else "community-picker-prepared-play.json"
        File(context.getExternalFilesDir("communication-qa")!!, reportName).writeText(JSONObject()
            .put("tapToPlayingMs", playbackMs).put("oneTapPlayback", true)
            .put("catalogReviewDelayMs", reviewDelay).put("noAutoplay", true)
            .put("originalTrackSelected", true).put("trimWithinVideoAndTrack", true).toString(2))
        assertTrue("First community music playback still takes too long: $playbackMs ms", playbackMs < 5_000)
    }
}
