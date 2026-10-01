package conectate.com

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.presentation.videos.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native Media3 export with a synthetic solid-red LUT; no accounts, server writes or personal media. */
class EditorColorLutDeviceAuditTest {
    @get:Rule val compose = createComposeRule()

    @Test fun importedColorFilterIsVisibleAndRenderedIntoTheExport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val marker = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "color-lut-source-$marker.mp4")
        val input = File(context.cacheDir, "file-provider/crops/color-lut-$marker.cube")
        var output: File? = null
        var model: VideoEditorViewModel? = null
        var lutId: String? = null
        try {
            instrumentation.context.assets.open("video-cache-smoke.mp4").use { from -> source.outputStream().use { from.copyTo(it) } }
            input.parentFile!!.mkdirs()
            input.writeText("TITLE \"Rojo de prueba\"\nLUT_3D_SIZE 2\n" + List(8) { "1 0 0" }.joinToString("\n"))
            instrumentation.runOnMainSync {
                model = VideoEditorViewModel(SavedStateHandle())
                model!!.getPlayer(context)
                model!!.setInitialVideo(context, Uri.fromFile(source))
            }
            val editor = model!!
            compose.waitUntil(20_000) { editor.timeline.value.tracks.flatMap { it.clips }.any { it.type == ClipType.VIDEO } }
            val clip = editor.timeline.value.tracks.flatMap { it.clips }.first { it.type == ClipType.VIDEO }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", input)
            instrumentation.runOnMainSync { editor.importColorLut(context, clip.id, uri) }
            compose.waitUntil(20_000) { !editor.isImportingLut.value }
            lutId = editor.timeline.value.tracks.flatMap { it.clips }.first { it.id == clip.id }.customLutId
            assertNotNull("Filter was not attached: ${editor.editorHint.value}", lutId)
            compose.setContent { MaterialTheme { VideoEditorScreen(rememberNavController(), Uri.fromFile(source), viewModel = editor) } }
            compose.onNodeWithText("Filtros").performClick()
            compose.onNodeWithText("Importar .cube").assertIsDisplayed().assertHasClickAction()
            val folder = context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(folder, "editor-imported-color-lut.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val done = CountDownLatch(1)
            var exported: Uri? = null
            var error: String? = null
            instrumentation.runOnMainSync { editor.exportVideo(context, { exported = it; done.countDown() }, { error = it; done.countDown() }) }
            assertTrue("Native export timed out", done.await(90, TimeUnit.SECONDS))
            assertNull("Native export failed", error)
            val exportUri = checkNotNull(exported)
            output = resolveOwnedEditorExportFile(context, exportUri)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(context, exportUri)
                val frame = checkNotNull(reader.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                val color = frame.getPixel(frame.width / 2, frame.height / 2)
                assertTrue("Imported filter did not render red", Color.red(color) > 180 && Color.green(color) < 70 && Color.blue(color) < 70)
                File(folder, "editor-lut-export-frame.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
            } finally { reader.release() }
            File(context.filesDir, "editor-color-lut-audit.json").writeText("""{"imported":true,"nativeExport":true,"renderedRedPixel":true,"scope":"Synthetic local video and SDR 2-point LUT"}""")
        } finally {
            instrumentation.runOnMainSync { model?.let { ViewModelStore().apply { put("qa", it); clear() } } }
            source.delete()
            input.delete()
            output?.delete()
            lutId?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { File(context.filesDir, "editor-color-luts/$it.cube").delete() }
        }
    }
}
