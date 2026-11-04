package conectate.com.presentation.video.routes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.EventListener
import coil.request.ImageRequest
import coil.request.SuccessResult
import conectate.com.core.testing.MediaAuditEntryPoint
import conectate.com.data.model.Video
import conectate.com.presentation.theme.RedSocialTheme
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Read-only visual QA with actual public covers. No member account or playback event is created. */
class VideoRoutePublicCoversDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun authorizedPublicCoversRenderInTheLightAndDarkRouteLayouts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLiveGuestRead") == "true")
        val context = instrumentation.targetContext
        val guest = EntryPointAccessors.fromApplication(context, MediaAuditEntryPoint::class.java).guestModeApi()
        val hadGuest = guest.hasActiveGuest()
        val preview = guest.beginMediaPreview()
        val original = Coil.imageLoader(context)
        val completed = ConcurrentHashMap.newKeySet<String>()
        val monitored = original.newBuilder().eventListener(object : EventListener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                completed.add(request.data.toString().substringBefore('?'))
            }
        }).build()
        val visible = mutableStateOf(true)
        val dark = mutableStateOf(false)
        var contentInstalled = false
        try {
            val videos = runBlocking(Dispatchers.IO) {
                guest.ensureIdentity()
                guest.videos().filter { routeThumbnailReference(it.thumbnailUrl.orEmpty()).isNotBlank() }.take(3).map {
                    Video(id = it.id, userId = it.userId, userName = it.userName, title = it.title,
                        videoUrl = it.videoUrl.orEmpty(), thumbnailUrl = it.thumbnailUrl.orEmpty())
                }
            }
            val route = checkNotNull(prepareVideoRoute("Para descubrir", 3, videos))
            assertTrue("The public catalog needs a cover to exercise actual image loading", route.items.isNotEmpty())
            Coil.setImageLoader(monitored)
            compose.setContent {
                RedSocialTheme(darkTheme = dark.value) {
                    if (visible.value) VideoRoutesContent(
                        state = VideoRoutesState(accountId = "public-visual-qa", route = route, loading = false, composerVisible = false),
                        onBack = {}, onQueryChange = {}, onSizeChange = {}, onPrepare = {}, onNewRoute = {}, onShowRoute = {},
                        onOpen = {}, onUseful = {}, onConclusionChange = {}, onSave = {}, onSaveAndBack = {},
                        onCompleted = {}, onErase = {}, onReload = {},
                    )
                }
            }
            contentInstalled = true
            compose.waitUntil(20_000) {
                route.items.all { item -> completed.any { url -> url.endsWith(item.thumbnailPath) } }
            }
            assertEquals(route.items.map { it.thumbnailPath }.distinct().size, completed.size)
            saveScreenshot("routes106-public-light.png")
            saveDisplayScreenshot("routes106-public-light-display.png")
            compose.runOnIdle { dark.value = true }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            saveScreenshot("routes106-public-dark.png")
            saveDisplayScreenshot("routes106-public-dark-display.png")
        } finally {
            if (contentInstalled) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            }
            Coil.setImageLoader(original)
            monitored.shutdown()
            guest.endMediaPreview(preview)
            if (!hadGuest) guest.clear()
        }
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(checkNotNull(context.getExternalFilesDir(null)), name)
        path.outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun saveDisplayScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 2_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val path = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name)
            path.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
