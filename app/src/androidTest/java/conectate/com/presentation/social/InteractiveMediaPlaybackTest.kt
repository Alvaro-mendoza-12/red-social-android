package conectate.com.presentation.social

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.music.ApprovedMomentMusic
import conectate.com.presentation.components.CommentAudioPlayer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File

/** Uses only explicit, owned QA objects. No session changes or media publication. */
@RunWith(AndroidJUnit4::class)
class InteractiveMediaPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun waitForTheForegroundAudioHost() {
        compose.waitUntil(5_000) {
            val process = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(process)
            compose.activity.hasWindowFocus() && process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
    private val qaOwner = "0e990f73-424e-4c33-9d83-5887f995e294"
    private fun source(key: String, bucket: String): String {
        val url = InstrumentationRegistry.getArguments().getString(key).orEmpty()
        assumeTrue("Provide owned QA media", url.startsWith("https://") &&
            android.net.Uri.parse(url).path.orEmpty().startsWith("/storage/v1/object/public/$bucket/$qaOwner/"))
        return url
    }
    private fun ready(label: String, start: Long) {
        android.util.Log.i("QaInteractiveMedia", "${label}_ready_ms=${SystemClock.elapsedRealtime() - start}")
    }
    private fun waitForPause() {
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Pausar").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val listen = compose.onAllNodesWithContentDescription("Escuchar").fetchSemanticsNodes().size
            val replay = compose.onAllNodesWithContentDescription("Reproducir").fetchSemanticsNodes().size
            val errors = compose.onAllNodes(hasText("No se pudo", substring = true)).fetchSemanticsNodes().size
            android.util.Log.i("QaInteractiveMedia", "audio_ui_timeout listen=$listen replay=$replay errors=$errors")
            throw timeout
        }
    }
    @Test fun profileAudioStartsAndCanPauseAndResume() {
        val audio = source("qaAudioUrl", "profile-audio")
        compose.setContent { MaterialTheme { CommentAudioPlayer(audio) } }
        val start = SystemClock.elapsedRealtime()
        compose.onNodeWithContentDescription("Reproducir").performClick()
        waitForPause(); ready("profile_audio", start)
        compose.onNodeWithContentDescription("Pausar").performClick()
        compose.onNodeWithContentDescription("Reproducir").assertIsDisplayed().performClick()
        waitForPause()
    }
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
    @Test fun profileAudioSurvivesAReplacementOfTheLifecycleOwnerAndStopsInBackground() {
        val audio = source("qaAudioUrl", "profile-audio")
        val owner = mutableStateOf<Owner?>(null)
        compose.runOnIdle { owner.value = Owner() }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner.value!!) {
            MaterialTheme { CommentAudioPlayer(audio) }
        } }
        compose.onNodeWithContentDescription("Reproducir").performClick(); waitForPause()
        compose.runOnIdle { owner.value = Owner() }
        compose.onNodeWithContentDescription("Pausar").performClick()
        compose.onNodeWithContentDescription("Reproducir").performClick(); waitForPause()
        compose.runOnIdle { owner.value!!.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(2000) { compose.onAllNodesWithContentDescription("Reproducir").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun selectedCommunityAudioStartsFromTheChosenRange() {
        val audio = source("qaAudioUrl", "profile-audio")
        val track = ApprovedMomentMusic("community:qa", "Pista QA", "Novvin QA", 12_000,
            audio, "https://example.invalid/qa", source = "novvin_community", license = "Audio propio")
        compose.setContent { MaterialTheme { MomentMusicPickerDialog(
            state = MusicCatalogUiState(items = listOf(track)), contentDurationMs = 6000, hasOriginalAudio = false,
            initialSelection = MomentMusicSelection(track, 1000, 7000, 1f, 0f),
            onSearch = { _, _, _ -> }, onLoadMore = {}, onToggleFavorite = {}, onReport = {},
            onNotInterested = {}, onPreviewStarted = {}, onOpenCommunityUpload = {}, onOpenDeviceMusic = {},
            onDismiss = {}, onRemove = {}, onSelect = {},
        ) } }
        val start = SystemClock.elapsedRealtime()
        compose.onNodeWithContentDescription("Escuchar").performClick()
        waitForPause(); ready("community_audio_preview", start)
        compose.onNodeWithContentDescription("Pausar").performClick()
    }
    @Test fun cameraPhotoLoadsThroughABoundedStorageVariant() {
        val photo = source("qaPhotoUrl", "moment-media")
        val thumb = source("qaThumbnailUrl", "moment-media")
        var loaded = false
        val start = SystemClock.elapsedRealtime()
        compose.setContent { MaterialTheme { Box(Modifier.size(360.dp, 650.dp)) {
            ZoomableMomentImage(photo, thumb, "qa-image", true) { loaded = true }
        } } }
        compose.waitUntil(10_000) { loaded }
        ready("moment_photo", start)
        compose.onNodeWithText("No se pudo cargar la imagen").assertDoesNotExist()
        compose.onNodeWithText("Cargando imagen…").assertDoesNotExist()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.externalCacheDir, "qa-moment-photo.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun brokenPhotoShowsAnExplicitRetry() {
        val photo = source("qaPhotoUrl", "moment-media") + ".missing.jpg"
        val thumb = source("qaThumbnailUrl", "moment-media")
        compose.setContent { MaterialTheme { Box(Modifier.size(360.dp, 650.dp)) {
            ZoomableMomentImage(photo, thumb, "qa-image-missing", true) {}
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No se pudo cargar la imagen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Reintentar").assertIsEnabled()
    }
}
