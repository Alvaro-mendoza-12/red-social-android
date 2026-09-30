package conectate.com

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.livekit.LiveKitRoomBridge
import conectate.com.data.model.LiveSfuSession
import conectate.com.data.signaling.SignalingConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A coordinator prepares an accepted guest and a battle, using isolated accounts. */
@RunWith(AndroidJUnit4::class)
class LiveDuetDeviceAuditTest {
    @get:Rule val compose = createComposeRule()
    @Test(timeout = 180_000) fun hostAndGuestReceiveActualAudioAndVideoDuringBattle() {
        val arguments = InstrumentationRegistry.getArguments()
        val role = arguments.getString("crossSfuRole")
        Assume.assumeTrue(role in listOf("host", "guest"))
        val liveId = checkNotNull(arguments.getString("crossSfuLive"))
        UUID.fromString(liveId)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val account = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText())
            .getJSONObject(if (role == "host") 0 else 1)
        val client = OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(20, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(SignalingConfig.BASE_URL + "/v1/data/live/$liveId/sfu-token")
            .header("Authorization", "Bearer " + account.getString("token"))
            .post(JSONObject().put("role", role).toString().toRequestBody("application/json".toMediaType())).build()
        val requestStarted = System.nanoTime()
        val session = client.newCall(request).execute().use { response ->
            assertTrue("Duet session HTTP ${response.code}", response.isSuccessful)
            val data = JSONObject(response.body!!.string())
            LiveSfuSession(wsUrl = data.getString("wsUrl"), roomName = data.getString("roomName"),
                token = data.getString("token"), role = role!!)
        }
        val sessionRequestMs = (System.nanoTime() - requestStarted) / 1_000_000
        val folder=context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        fun waitForPhase(name: String,seconds: Long): Boolean {
            val file=File(context.cacheDir,name)
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds)
            while(System.nanoTime()<deadline) {
                if(runCatching { JSONObject(file.readText()).optString("scopeId")==liveId }.getOrDefault(false)) return true
                Thread.sleep(100)
            }
            return false
        }
        val existingCapture = if (role == "host" && arguments.getString("auditExistingCamera") == "true") {
            conectate.com.data.webrtc.WebRTCManager(context, object : conectate.com.data.webrtc.SignalingEvents {
                override fun onSendOffer(targetSocketId: String, offer: JSONObject) = Unit
                override fun onSendAnswer(targetSocketId: String, answer: JSONObject) = Unit
                override fun onSendIceCandidate(targetSocketId: String, candidate: JSONObject) = Unit
            })
        } else null
        val existingFrames = AtomicInteger()
        val existingSink = org.webrtc.VideoSink { existingFrames.incrementAndGet() }
        var existingTrack: org.webrtc.VideoTrack? = null
        val bridge = LiveKitRoomBridge(context)
        val frames = AtomicInteger()
        val rendered = CountDownLatch(1)
        var renderer: io.livekit.android.renderer.TextureViewRenderer? = null
        var video: LiveKitRoomBridge.RemoteVideo? = null
        try {
            existingCapture?.let { manager ->
                val ready = CountDownLatch(1)
                var success = false
                manager.startVideo(false, isFront = true, targetWidth = 640, targetHeight = 360,
                    targetFps = 24, onComplete = { success = it; ready.countDown() })
                assertTrue("Existing camera setup timed out", ready.await(15, TimeUnit.SECONDS))
                assertTrue("Existing camera failed", success)
                existingTrack = manager.localVideoTrack.value
                assertNotNull(existingTrack)
                existingTrack!!.addSink(existingSink)
                val firstCaptureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (existingFrames.get() == 0 && System.nanoTime() < firstCaptureDeadline) Thread.sleep(100)
                assertTrue("Existing camera has no frames", existingFrames.get() > 0)
            }
            val started = System.nanoTime()
            runBlocking { withTimeout(35_000) { bridge.connect(session, true, true, existingCapture?.localVideoTrack).getOrThrow() } }
            val connectMs = (System.nanoTime() - started) / 1_000_000
            val timings = checkNotNull(bridge.lastStartupTimings)
            val startup = JSONObject().put("roomCreationMs", timings.roomCreationMs)
                .put("signalingMs", timings.signalingMs).put("audioMs", timings.audioMs)
                .put("videoMs", timings.videoMs).put("totalMs", timings.totalMs)
            val roomField = bridge.javaClass.getDeclaredField("room").apply { isAccessible = true }
            val room = roomField.get(bridge) as io.livekit.android.room.Room
            val sources = room.localParticipant.trackPublications.values.map { it.source }.toSet()
            assertTrue("Parallel startup lost microphone publication", io.livekit.android.room.track.Track.Source.MICROPHONE in sources)
            assertTrue("Parallel startup lost camera publication", io.livekit.android.room.track.Track.Source.CAMERA in sources)
            File(folder, "sfu-published-$role.json").writeText(JSONObject()
                .put("scopeId", liveId).put("sessionRequestMs", sessionRequestMs)
                .put("connectMs", connectMs).toString())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (bridge.remoteVideos.value.isEmpty() && System.nanoTime() < deadline) Thread.sleep(100)
            assertTrue("No guest/host video track", bridge.remoteVideos.value.isNotEmpty())
            video = bridge.remoteVideos.value.first()
            instrumentation.runOnMainSync {
                renderer = object : io.livekit.android.renderer.TextureViewRenderer(context) {
                    override fun onFrame(frame: livekit.org.webrtc.VideoFrame) {
                        frames.incrementAndGet(); super.onFrame(frame)
                    }
                }
                assertTrue(bridge.attachRenderer(video!!, renderer!!))
                renderer!!.addFrameListener({ bitmap -> bitmap?.recycle(); rendered.countDown() }, 0.1f)
            }
            compose.setContent { MaterialTheme { AndroidView(factory = { renderer!! }, modifier = Modifier.fillMaxSize()) } }
            assertTrue("No decoded/drawn duet image", rendered.await(20, TimeUnit.SECONDS))
            val firstFrameMs = (System.nanoTime() - started) / 1_000_000
            File(folder,"sfu-ready-$role.json").writeText(JSONObject().put("scopeId",liveId).toString())
            assertTrue("Coordinator did not start both media samples",waitForPhase("sfu-sample-now.json",40))
            val existingBefore = existingFrames.get()
            val before = frames.get(); val sample = System.nanoTime(); Thread.sleep(10_000)
            val fps = (frames.get() - before) * 1_000_000_000.0 / (System.nanoTime() - sample)
            if (existingCapture != null) assertTrue("Battle interrupted existing broadcast capture", existingFrames.get() > existingBefore)
            runBlocking { bridge.connect(session, true, true, existingCapture?.localVideoTrack).getOrThrow() }
            assertSame("Duplicate connection replaced the active room", room, roomField.get(bridge))
            val statsReady = CountDownLatch(2)
            val reports = java.util.concurrent.ConcurrentLinkedQueue<livekit.org.webrtc.RTCStatsReport>()
            room.getSubscriberRTCStats { reports.add(it); statsReady.countDown() }
            room.getPublisherRTCStats { reports.add(it); statsReady.countDown() }
            assertTrue(statsReady.await(5, TimeUnit.SECONDS))
            // Depending on SFU negotiation, incoming media can be associated
            // with either transport. Inspect both, keeping audio mandatory.
            val incomingStats = reports.flatMap { it.statsMap.values }.distinctBy { it.id }
            val audio = incomingStats.any {
                it.type == "inbound-rtp" && it.members["kind"] == "audio" &&
                    ((it.members["packetsReceived"] as? Number)?.toLong() ?: 0) > 0
            }
            val statsDetails=JSONArray(incomingStats.filter { it.type=="inbound-rtp" }.map {
                JSONObject().put("kind",it.members["kind"]).put("mediaType",it.members["mediaType"])
                    .put("packetsReceived",it.members["packetsReceived"]).put("bytesReceived",it.members["bytesReceived"])
                    .put("framesDecoded",it.members["framesDecoded"])
            })
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "sfu-diagnostics-$role.json")
                .writeText(JSONObject().put("receivedFps",fps).put("audioReceived",audio)
                    .put("publishedTracks",room.localParticipant.trackPublications.values.map { it.kind.toString()+":"+it.muted })
                    .put("inbound",statsDetails).toString(2))
            assertTrue("No incoming audio on $role", audio)
            assertTrue("Duet image is too slow: $fps", fps >= 8)
            if (existingCapture != null) {
                fun waitForCamera(muted: Boolean): Boolean {
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                    while (System.nanoTime() < until) {
                        val pubs = room.localParticipant.trackPublications.values
                        if (pubs.any { it.source == io.livekit.android.room.track.Track.Source.CAMERA && it.muted == muted } &&
                            pubs.any { it.source == io.livekit.android.room.track.Track.Source.MICROPHONE && it.muted == muted }) return true
                        Thread.sleep(100)
                    }
                    return false
                }
                val localTrack = room.localParticipant.getTrackPublication(io.livekit.android.room.track.Track.Source.CAMERA)?.track
                    as io.livekit.android.room.track.LocalVideoTrack
                val sharedFrames = AtomicInteger()
                val sharedSink = livekit.org.webrtc.VideoSink { sharedFrames.incrementAndGet() }
                localTrack.addRenderer(sharedSink)
                try {
                    bridge.updateMediaEnabled(false, false)
                    assertTrue("Collaboration controls did not mute both publications", waitForCamera(true))
                    val nativeBefore = existingFrames.get()
                    Thread.sleep(500)
                    assertTrue("Muting battle stopped native broadcast", existingFrames.get() > nativeBefore)
                    runBlocking { existingCapture.stopVideoAwait() }
                    val ready = CountDownLatch(1)
                    var started = false
                    existingCapture.startVideo(false, isFront = false, targetWidth = 640, targetHeight = 360,
                        targetFps = 24, onComplete = { started = it; ready.countDown() })
                    assertTrue("Replacement capture timed out", ready.await(15, TimeUnit.SECONDS))
                    assertTrue("Replacement capture failed", started)
                    bridge.updateMediaEnabled(true, true)
                    assertTrue("Collaboration controls did not resume both publications", waitForCamera(false))
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while(sharedFrames.get() < 5 && System.nanoTime() < until) Thread.sleep(100)
                    assertTrue("Shared capture did not follow the replacement native track", sharedFrames.get() >= 5)
                } finally {
                    localTrack.removeRenderer(sharedSink)
                }
            }
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "sfu-duet-$role.json")
                .writeText(JSONObject().put("scopeId", liveId).put("role", role).put("audioReceived", audio)
                    .put("rendered", true).put("existingBroadcastPreserved",existingCapture != null).put("controlsAndReplacementCapture",existingCapture != null).put("receivedFps", fps).put("firstFrameMs", firstFrameMs).put("connectMs", connectMs).put("sessionRequestMs", sessionRequestMs).put("subscribeAndRenderMs", firstFrameMs - connectMs).put("startup", startup).toString(2))
        } finally {
            File(folder,"sfu-done-$role.json").writeText(JSONObject().put("scopeId",liveId).toString())
            waitForPhase("sfu-stop-now.json",20)
            instrumentation.runOnMainSync { if (video != null && renderer != null) bridge.detachRenderer(video!!, renderer!!) }
            runBlocking { bridge.disconnectAwait() }
            existingTrack?.let { runCatching { it.removeSink(existingSink) } }
            existingCapture?.let { manager ->
                val closed = CountDownLatch(1)
                manager.destroy { closed.countDown() }
                assertTrue("Existing camera did not close", closed.await(15, TimeUnit.SECONDS))
            }
        }
    }
}
