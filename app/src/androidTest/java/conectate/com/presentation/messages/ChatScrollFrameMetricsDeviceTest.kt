package conectate.com.presentation.messages

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.content.Intent
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.Message
import conectate.com.presentation.components.MessageBubble
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/**
 * Physical-device diagnostic using real message bubbles and native touch/fling.
 * No network or real conversations. Debug timings are evidence, not a release
 * benchmark or a device-independent FPS assertion.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class ChatScrollFrameMetricsDeviceTest {
    @Test fun nativeFlingMovesHistoryAndRecordsFrameDeadlines() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val messages = (1..400).map { index ->
            Message(
                id = "frame-row-$index", sender_id = if (index % 3 == 0) "me" else "other",
                content = when (index % 4) {
                    0 -> "Mensaje $index: texto de prueba para el historial y su desplazamiento natural."
                    1 -> "Hola 👩🏽‍💻 🇵🇪 ❤️ $index"
                    2 -> "😀 👍🏽"
                    else -> "Mensaje $index\nUna segunda línea de texto."
                },
                content_type = "text", created_at = "2026-10-24T00:23:40Z",
            )
        }
        val state = AtomicReference<LazyListState>()
        // ActivityScenario uses the real Choreographer clock. A Compose test
        // rule would advance its virtual animation clock while waiting for idle,
        // distorting any wall-clock FrameMetrics taken during a fling.
        ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, ComponentActivity::class.java)).use { scenario ->
            lateinit var window: Window
            var x = 0f
            var startY = 0f
            var endY = 0f
            scenario.onActivity { activity ->
                window = activity.window
                activity.setContent {
                    MaterialTheme {
                        val listState = rememberConversationListState()
                        SideEffect { state.set(listState) }
                        ConversationScrollAnchor("frame-diagnostic", messages, "me", null, listState,
                            Message::id, Message::sender_id)
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(messages, key = Message::id, contentType = { it.content_type }) { message ->
                                MessageBubble(message, message.sender_id == "me")
                            }
                        }
                    }
                }
            }
            fun onMain(condition: () -> Boolean): Boolean {
                var result = false
                instrumentation.runOnMainSync { result = condition() }
                return result
            }
            fun awaitState(condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (!onMain(condition) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
                assertTrue("Conversation did not settle in time", onMain(condition))
            }
            awaitState { state.get()?.layoutInfo?.visibleItemsInfo?.any { it.key == "frame-row-400" } == true }
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val location = IntArray(2).also(decor::getLocationOnScreen)
                x = location[0] + decor.width * .5f
                startY = location[1] + decor.height * .25f
                endY = location[1] + decor.height * .75f
            }
            fun nativeSwipe() {
                val downTime = SystemClock.uptimeMillis()
                fun event(action: Int, y: Float) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue("Native touch injection failed", instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                event(MotionEvent.ACTION_DOWN, startY)
                repeat(10) { step ->
                    SystemClock.sleep(14)
                    event(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (step + 1) / 10f)
                }
                event(MotionEvent.ACTION_UP, endY)
                SystemClock.sleep(50)
                awaitState { !state.get().isScrollInProgress }
            }
            // Warm layout, font fallback and touch dispatch before recording.
            nativeSwipe()
            var before = 0
            scenario.onActivity { before = state.get().firstVisibleItemIndex }
            val samples = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
            val droppedCallbacks = AtomicInteger()
            val thread = HandlerThread("novvin-qa-frames").apply { start() }
            val handler = Handler(thread.looper)
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                droppedCallbacks.addAndGet(dropped)
                if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                    samples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) to metrics.getMetric(FrameMetrics.DEADLINE))
                }
            }
            scenario.onActivity { window.addOnFrameMetricsAvailableListener(listener, handler) }
            try {
                repeat(6) { nativeSwipe() }
            } finally {
                scenario.onActivity { window.removeOnFrameMetricsAvailableListener(listener) }
                val drained = CountDownLatch(1)
                handler.post { drained.countDown() }
                drained.await(5, TimeUnit.SECONDS)
                thread.quitSafely()
                thread.join(5_000)
            }
            var after = 0
            scenario.onActivity { after = state.get().firstVisibleItemIndex }
            assertTrue("The native gesture did not move through history", after < before)
            val frames = synchronized(samples) { samples.toList() }
            assertTrue("No render frames were captured", frames.isNotEmpty())
            val sorted = frames.map { it.first / 1_000_000.0 }.sorted()
            fun percentile(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)]
            val result = JSONObject().apply {
                put("scenario", "400 real text bubbles, native touch/fling, real Choreographer, warmed fonts, no network")
                put("build", "audit-debug; not a release FPS certification")
                put("frames", frames.size)
                put("frameTotalP50Ms", percentile(.50))
                put("frameTotalP95Ms", percentile(.95))
                put("frameTotalMaxMs", sorted.last())
                put("framesMissingDeadline", frames.count { (duration, deadline) -> deadline > 0 && duration >= deadline })
                put("droppedMetricCallbacks", droppedCallbacks.get())
                put("firstVisibleBefore", before)
                put("firstVisibleAfter", after)
            }
            val destination = File(instrumentation.targetContext.filesDir, "qa-metrics/chat-scroll-frames.json")
            destination.parentFile!!.mkdirs()
            destination.writeText(result.toString(2))
            Log.i("NovvinScrollQA", result.toString())
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHAT_SCROLL_METRICS $result\n") })
        }
    }
}
