package conectate.com

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.repository.LiveRepository
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import conectate.com.presentation.components.WebRtcVideoView
import dagger.hilt.android.EntryPointAccessors
import io.socket.client.Socket
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An owned external host avoids benchmarking two media engines on the viewer device. */
@RunWith(AndroidJUnit4::class)
class LiveViewerSeparateHostAuditTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 120_000) fun actualViewerJoinsOwnedHostOnAnotherDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("auditSeparateLiveHost") == "true")
        val scopeId = UUID.fromString(args.getString("ownedLiveFixtureId")).toString()
        val context = instrumentation.targetContext
        val fixtures = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText())
        val ownedIds = (0 until fixtures.length()).map { fixtures.getJSONObject(it).getString("id") }.toSet()
        assertEquals(2, ownedIds.size)
        val entry = EntryPointAccessors.fromApplication(context, MessageNotificationReceiverEntryPoint::class.java)
        compose.waitUntil(15_000) { !entry.authService().isInitializing.value }
        val owner = requireNotNull(entry.authService().getCurrentUserId())
        assertTrue("Only disposable QA accounts are allowed", owner in ownedIds)
        val provider = entry.javaClass.getDeclaredField("liveRepositoryProvider").apply { isAccessible = true }.get(entry) as Provider<*>
        val repository = provider.get() as LiveRepository
        assertNull("Do not interrupt another LIVE", repository.activeSession.value)
        val stream = runBlocking { repository.getLiveStreamById(scopeId).getOrThrow() }
        assertTrue("External host must be the other owned QA account", stream.hostUserId in ownedIds && stream.hostUserId != owner)
        val ready = File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "external-live-viewer-ready-private.json")
        ready.delete()
        val firstFrameAt = AtomicLong()
        val shown = mutableStateOf(true)
        compose.setContent {
            val track = repository.remoteVideoTracks.collectAsState().value.values.firstOrNull()
            val egl = repository.eglBaseContext.collectAsState().value
            MaterialTheme {
                if (shown.value) WebRtcVideoView(track, egl, Modifier.fillMaxSize(),
                    onFirstFrameRendered = { firstFrameAt.compareAndSet(0, System.nanoTime()) })
            }
        }
        try {
            val start = System.nanoTime()
            runBlocking { repository.joinLiveAsViewer(scopeId).getOrThrow() }
            val requestMs = (System.nanoTime() - start) / 1_000_000
            fun socket() = repository.javaClass.getDeclaredField("socket").apply { isAccessible = true }.get(repository) as? Socket
            compose.waitUntil(10_000) { !socket()?.id().isNullOrBlank() }
            ready.writeText(JSONObject().put("scopeId", scopeId).put("socketId", socket()!!.id()).put("userId", owner).toString())
            compose.waitUntil(25_000) { firstFrameAt.get() > 0L }
            val fullMs = (firstFrameAt.get() - start) / 1_000_000
            assertEquals(owner, entry.authService().getCurrentUserId())
            File(ready.parentFile, "live-separate-host-full-join.json").writeText(JSONObject()
                .put("joinRequestMs", requestMs).put("joinToRenderedFirstFrameMs", fullMs)
                .put("actualRepositoryAndRenderer", true).put("hostOnSeparateDevice", true)
                .put("includesCoordinatorPollingOverhead", true).put("humanSessionNeverReplaced", true).toString(2))
            // Keep the renderer and session alive while the external host verifies RTP.
            Thread.sleep(25_000)
            assertTrue("LIVE first picture exceeds the five-second audit budget: $fullMs ms", fullMs < 5_000)
        } finally {
            compose.runOnIdle { shown.value = false }
            if (repository.activeSession.value?.stream?.id == scopeId) runBlocking { repository.endLive().getOrThrow() }
            ready.delete()
            assertEquals(owner, entry.authService().getCurrentUserId())
        }
    }
}
