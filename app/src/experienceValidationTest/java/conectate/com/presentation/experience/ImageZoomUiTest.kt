package conectate.com.presentation.experience

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import conectate.com.data.model.Moment
import conectate.com.data.localfallback.AnonymousChatMessage
import conectate.com.data.localfallback.AdminRecoveryEvidence
import conectate.com.presentation.proximity.AnonymousMessageBubble
import conectate.com.presentation.profile.RecoveryEvidenceSection
import androidx.compose.foundation.layout.Column
import conectate.com.presentation.components.FullScreenImageDialog
import conectate.com.presentation.components.MediaItem
import conectate.com.presentation.social.MomentContent
import conectate.com.presentation.social.MomentViewerGestureLayer
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/** Production viewers and navigation gestures, loading an owned synthetic file offline. */
class ImageZoomUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var source: File
    private lateinit var oldLoader: ImageLoader
    private lateinit var testLoader: ImageLoader
    private var rejectPreview = false
    @Before fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        source = File(context.cacheDir,"zoom-synthetic.png")
        val bitmap = Bitmap.createBitmap(256,160,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint()
        paint.color = android.graphics.Color.BLUE; canvas.drawRect(0f,0f,128f,160f,paint)
        paint.color = android.graphics.Color.YELLOW; canvas.drawRect(128f,0f,256f,160f,paint)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        oldLoader = Coil.imageLoader(context)
        testLoader = ImageLoader.Builder(context).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): coil.request.ImageResult =
                    if (rejectPreview && chain.request.memoryCacheKey?.key?.endsWith("#fullscreen") != true)
                        ErrorResult(null,chain.request,IOException("Synthetic missing preview"))
                    else chain.proceed(chain.request)
            })
        }.build()
        Coil.setImageLoader(testLoader)
    }
    @After fun cleanup() { Coil.setImageLoader(oldLoader); testLoader.shutdown(); source.delete() }
    private fun scale(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun waitZoomReady() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("media_zoom_viewer").fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsActions.CustomActions) == true
    }
    private fun capture(tag: String,name: String) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"experience-audit").apply { mkdirs() }
        File(folder,name).outputStream().use { compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun originalWithoutPreviewSupportsDoubleTapPinchPanAndResetInsideSocialDialog() {
        rejectPreview = true; var dismissed = false
        compose.setContent { MaterialTheme { FullScreenImageDialog(mediaItems=listOf(MediaItem("qa-photo",Uri.fromFile(source).toString(),"image")),onDismiss={dismissed=true}) } }
        waitZoomReady(); assertEquals("Ampliación 100 %",scale("media_zoom_viewer"))
        val node = compose.onNodeWithTag("media_zoom_viewer")
        node.performTouchInput { doubleClick(center) }; compose.waitForIdle()
        assertEquals("Ampliación 200 %",scale("media_zoom_viewer"))
        node.performTouchInput { swipe(center,center+Offset(80f,100f)) }; assertFalse(dismissed)
        capture("media_zoom_viewer","social-zoom.png")
        node.performTouchInput { doubleClick(center) }; compose.waitForIdle()
        assertEquals("Ampliación 100 %",scale("media_zoom_viewer"))
        node.performTouchInput {
            down(0,center-Offset(70f,0f)); down(1,center+Offset(70f,0f))
            for(i in 1..12) { moveTo(0,center-Offset(70f+i*6f,0f)); moveTo(1,center+Offset(70f+i*6f,0f)) }
            up(0); up(1)
        }
        compose.waitForIdle(); assertNotEquals("Ampliación 100 %",scale("media_zoom_viewer")); assertFalse(dismissed)
    }
    @Test fun accessibilityCanEnlargeReduceAndResetTheSameViewer() {
        compose.setContent { MaterialTheme { FullScreenImageDialog(imageUrl=Uri.fromFile(source).toString(),onDismiss={}) } }
        waitZoomReady()
        fun act(label: String) {
            val actions=compose.onNodeWithTag("media_zoom_viewer").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            compose.runOnIdle { assertTrue(actions.first { it.label==label }.action()) }
        }
        act("Ampliar imagen"); assertEquals("Ampliación 150 %",scale("media_zoom_viewer"))
        act("Reducir imagen"); assertEquals("Ampliación 100 %",scale("media_zoom_viewer"))
        act("Ampliar imagen"); act("Restablecer imagen"); assertEquals("Ampliación 100 %",scale("media_zoom_viewer"))
    }
    @Test fun photoMomentZoomPassesThroughNavigationLayerPausesAndKeepsEdgeNavigation() {
        var settled=false; var zoomed=false;var next=0;var close=0
        val moment=Moment(id="qa-moment",userId="qa-owner",mediaUrl=Uri.fromFile(source).toString(),mediaType="image")
        compose.setContent { MaterialTheme {
            MomentViewerGestureLayer(isOwner=false,onPauseChanged={},onPrevious={},onNext={next++},onOpenViewers={},onClose={close++}) {
                MomentContent(moment=moment,isVisible=true,paused=false,onVideoDurationKnown={_,_->},onVideoReady={},
                    onImageReady={settled=true},onVideoProgress={_,_->},onVideoEnded={},onImageZoomChanged={zoomed=it},
                    onImageTap={if(it>.75f)next++})
            }
        } }
        compose.waitUntil(15_000) { settled }
        val node=compose.onNodeWithTag("moment_zoom_viewer")
        node.performTouchInput { doubleClick(center) };compose.waitForIdle()
        assertEquals("Ampliación 200 %",scale("moment_zoom_viewer"));assertTrue(zoomed)
        node.performTouchInput { swipe(center,center+Offset(70f,100f)) };assertEquals(0,close);assertEquals(0,next)
        capture("moment_zoom_viewer","moment-zoom.png")
        node.performTouchInput { doubleClick(center) };compose.waitForIdle()
        assertEquals("Ampliación 100 %",scale("moment_zoom_viewer"));assertFalse(zoomed)
        node.performTouchInput { click(Offset(width*.9f,height*.5f)) };compose.mainClock.advanceTimeBy(500);compose.waitForIdle()
        assertEquals(1,next)
    }
    @Test fun anonymousPhotoOpensCommonViewerAndCanZoom() {
        val message=AnonymousChatMessage(id="qa-image",clientMessageId="qa-client",sender="peer",content="Foto",contentType="image",mediaUrl=Uri.fromFile(source).toString(),createdAt="2026-10-08T10:00:00Z")
        compose.setContent { MaterialTheme { AnonymousMessageBubble(message) } }
        compose.onNodeWithContentDescription("Foto").performClick();waitZoomReady()
        compose.onNodeWithTag("media_zoom_viewer").performTouchInput { doubleClick(center) }
        assertEquals("Ampliación 200 %",scale("media_zoom_viewer"))
        capture("media_zoom_viewer","anonymous-zoom.png")
    }
    @Test fun recoveryEvidenceOpensSameViewerAndCanZoom() {
        val bytes=source.readBytes()
        val evidence=AdminRecoveryEvidence("qa-owned-evidence","evidencia-qa.png","image/png",bytes.size)
        compose.setContent { MaterialTheme { Column { RecoveryEvidenceSection(listOf(evidence)) { Result.success(bytes) } } } }
        compose.onNode(hasText("Abrir evidencia-qa.png",substring=true)).performClick();waitZoomReady()
        compose.onNodeWithTag("media_zoom_viewer").performTouchInput { doubleClick(center) }
        assertEquals("Ampliación 200 %",scale("media_zoom_viewer"))
        capture("media_zoom_viewer","recovery-zoom.png")
    }
}
