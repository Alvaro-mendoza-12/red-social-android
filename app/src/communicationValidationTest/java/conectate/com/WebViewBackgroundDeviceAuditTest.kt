package conectate.com

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Offline real WebView media; only the disposable emulator may run this case. */
@RunWith(AndroidJUnit4::class)
class WebViewBackgroundDeviceAuditTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 90_000) fun externalPreviewClosesOnBackgroundAndDoesNotAutoplayOnReturn() {
        verifyPreview(false)
    }

    @Test(timeout = 90_000) fun actualAndroidHomeClosesTheWebPlayer() {
        verifyPreview(true)
    }

    private fun verifyPreview(usePlatformLifecycle: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditWebViewBackground") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Use only the owned emulator", android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.MODEL.contains("sdk_gphone"))
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
        val opened = mutableStateOf(true)
        var dismissals = 0
        var activity: android.app.Activity? = null
        var platformOwner: LifecycleOwner? = null
        compose.setContent {
            val hostOwner = LocalLifecycleOwner.current
            val localContext = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.SideEffect {
                platformOwner = hostOwner
                var current = localContext
                var remaining = 20
                while (current is android.content.ContextWrapper && current !is android.app.Activity && remaining-- > 0) current = current.baseContext
                activity = current as? android.app.Activity
            }
            CompositionLocalProvider(LocalLifecycleOwner provides if (usePlatformLifecycle) hostOwner else owner) {
                MaterialTheme {
                    if (opened.value) conectate.com.presentation.voicerooms.VoiceRoomLinkDialog(
                        "https://novvin-qa-does-not-exist.invalid", onDismiss = { dismissals++; opened.value = false })
                }
            }
        }
        fun views(): List<WebView> {
            val result = mutableListOf<WebView>()
            instrumentation.runOnMainSync {
                fun visit(view: View) {
                    if (view is WebView) result.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                WindowInspector.getGlobalWindowViews().forEach(::visit)
            }
            return result.distinct()
        }
        compose.waitUntil(10_000) { views().size == 1 }
        val web = views().single()
        // Silent WAV and silent Web Audio source avoid touching the user's volume.
        val samples = 22050 * 2
        val bytes = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(22050); putInt(44100); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
        val wav = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val html = """<!doctype html><audio id="sample" loop src="data:audio/wav;base64,$wav"></audio>
          <script>window.audio = document.getElementById('sample');
          window.ctx = new AudioContext(); window.source = ctx.createBufferSource();
          source.buffer = ctx.createBuffer(1, 44100, 44100); source.loop = true;
          source.connect(ctx.destination); source.start(); ctx.resume(); audio.play();</script>"""
        instrumentation.runOnMainSync {
            web.stopLoading()
            web.settings.mediaPlaybackRequiresUserGesture = false // Simulate an explicit foreground play gesture.
            web.loadDataWithBaseURL("https://novvin-qa-does-not-exist.invalid", html, "text/html", "UTF-8", null)
        }
        fun evaluate(script: String): String {
            val done = CountDownLatch(1); var result = "null"
            instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
            assertTrue("WebView evaluation timed out", done.await(5, TimeUnit.SECONDS))
            return result
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var playing = false
        while (System.nanoTime() < deadline && !playing) {
            playing = evaluate("Boolean(window.ctx && ctx.state === 'running' && ctx.currentTime > .1 && window.audio && !audio.paused && audio.currentTime > .1)") == "true"
            if (!playing) Thread.sleep(100)
        }
        assertTrue("Native WebView did not render both media sources before background", playing)
        val started = System.nanoTime()
        var removedWithoutComposeFrame = false
        compose.mainClock.autoAdvance = false
        try {
            if (usePlatformLifecycle) {
                assertNotNull(activity)
                assertTrue("Android Home action must be accepted", instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
                val closedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (views().isNotEmpty() && System.nanoTime() < closedBy) Thread.sleep(10)
            } else {
                instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.CREATED }
            }
            removedWithoutComposeFrame = views().isEmpty()
        } finally {
            compose.mainClock.autoAdvance = true
            if (usePlatformLifecycle) {
                val host = checkNotNull(activity)
                instrumentation.runOnMainSync { host.startActivity(android.content.Intent(host, host.javaClass)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            }
        }
        assertTrue("Destroy media immediately even when background recomposition has no frame", removedWithoutComposeFrame)
        compose.waitUntil(2_000) { !opened.value && views().isEmpty() }
        val closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals("Pause and stop must dismiss only once", 1, dismissals)
        if (usePlatformLifecycle) {
            val resumedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (platformOwner?.lifecycle?.currentState != Lifecycle.State.RESUMED && System.nanoTime() < resumedBy) Thread.sleep(50)
            assertEquals(Lifecycle.State.RESUMED, platformOwner?.lifecycle?.currentState)
        } else instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertFalse("Returning must not reopen the web player", opened.value)
        assertTrue(views().isEmpty())
        File(context.getExternalFilesDir("communication-qa")!!, (if (usePlatformLifecycle) "webview-background-platform.json" else "webview-background.json")).writeText(JSONObject()
            .put("htmlMediaAdvancedBeforeBackground", true).put("webAudioRenderedBeforeBackground", true)
            .put("nativeWebViewDetached", true).put("dismissals", dismissals).put("closeMs", closeMs)
            .put("disposalWithoutComposeFrame", removedWithoutComposeFrame)
            .put("closeBoundMs", 2000).put("noAutoplayOnReturn", true)
            .put("lifecycleDrivenByTestOwner", !usePlatformLifecycle).put("actualAndroidHome", usePlatformLifecycle).put("networkUsed", false).toString(2))
    }
}
