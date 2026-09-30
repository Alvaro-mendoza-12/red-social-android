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
        val session = client.newCall(request).execute().use { response ->
            assertTrue("Duet session HTTP ${response.code}", response.isSuccessful)
            val data = JSONObject(response.body!!.string())
            LiveSfuSession(wsUrl = data.getString("wsUrl"), roomName = data.getString("roomName"),
                token = data.getString("token"), role = role!!)
        }
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
        val bridge = LiveKitRoomBridge(context)
        val frames = AtomicInteger()
        val rendered = CountDownLatch(1)
        var renderer: io.livekit.android.renderer.TextureViewRenderer? = null
        var video: LiveKitRoomBridge.RemoteVideo? = null
        try {
            val started = System.nanoTime()
            runBlocking { withTimeout(35_000) { bridge.connect(session, true, true).getOrThrow() } }
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
            val before = frames.get(); val sample = System.nanoTime(); Thread.sleep(10_000)
            val fps = (frames.get() - before) * 1_000_000_000.0 / (System.nanoTime() - sample)
            val roomField = bridge.javaClass.getDeclaredField("room").apply { isAccessible = true }
            val room = roomField.get(bridge) as io.livekit.android.room.Room
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
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "sfu-duet-$role.json")
                .writeText(JSONObject().put("scopeId", liveId).put("role", role).put("audioReceived", audio)
                    .put("rendered", true).put("receivedFps", fps).put("firstFrameMs", firstFrameMs).toString(2))
        } finally {
            File(folder,"sfu-done-$role.json").writeText(JSONObject().put("scopeId",liveId).toString())
            waitForPhase("sfu-stop-now.json",20)
            instrumentation.runOnMainSync { if (video != null && renderer != null) bridge.detachRenderer(video!!, renderer!!) }
            runBlocking { bridge.disconnectAwait() }
        }
    }
}
