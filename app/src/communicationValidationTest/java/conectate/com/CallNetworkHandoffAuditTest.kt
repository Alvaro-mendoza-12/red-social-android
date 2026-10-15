package conectate.com

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.model.CallInvite
import conectate.com.data.repository.CallRepository
import conectate.com.data.service.IncomingCallReceiverEntryPoint
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import conectate.com.data.signaling.SignalingConfig
import conectate.com.data.webrtc.SignalingEvents
import conectate.com.data.webrtc.WebRTCManager
import conectate.com.data.webrtc.MediaTaskExecutor
import dagger.hilt.android.EntryPointAccessors
import io.socket.client.IO
import io.socket.client.Socket
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport

/** Opt-in two-device audit: only the owned receiver uses the production call repository. */
@RunWith(AndroidJUnit4::class)
class CallNetworkHandoffAuditTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun await(message: String, seconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        assertTrue(message, condition())
    }

    @Suppress("UNCHECKED_CAST")
    private fun stats(manager: WebRTCManager): RTCStatsReport? {
        val field = WebRTCManager::class.java.getDeclaredField("peerConnections").apply { isAccessible = true }
        val peers = field.get(manager) as ConcurrentHashMap<String, PeerConnection>
        val executor = WebRTCManager::class.java.getDeclaredField("executor").apply { isAccessible = true }
            .get(manager) as MediaTaskExecutor
        val latch = CountDownLatch(1)
        var result: RTCStatsReport? = null
        // Serialize native pointer access with peer close/dispose during handoffs.
        executor.execute(onUnavailable = { latch.countDown() }) {
            val peer = peers.values.firstOrNull()
            if (peer == null) latch.countDown()
            else runCatching { peer.getStats { result = it; latch.countDown() } }
                .onFailure { latch.countDown() }
        }
        assertTrue("Native statistics timed out", latch.await(3, TimeUnit.SECONDS))
        return result
    }

    private fun audioCounters(manager: WebRTCManager): Map<String, Long> =
        stats(manager)?.statsMap?.values.orEmpty().filter {
            it.members["kind"] == "audio" && it.type == "inbound-rtp"
        }.associate { it.id to ((it.members["packetsReceived"] as? Number)?.toLong() ?: 0L) }

    private fun transport(): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            else -> "other"
        }
    }

    @Test(timeout = 240_000)
    fun productionReceiverRecoversAudioAcrossWifiAndMobileData() {
        val args = InstrumentationRegistry.getArguments()
        val role = args.getString("networkHandoffRole")
        val relayExperiment = args.getString("networkAuditRelayExperiment") == "true"
        Assume.assumeTrue(role in listOf("caller", "receiver"))
        val scope = UUID.fromString(checkNotNull(args.getString("networkHandoffScope"))).toString()
        val fixtures = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText())
        require(fixtures.length() == 2)
        val receiver = fixtures.getJSONObject(0)
        val caller = fixtures.getJSONObject(1)
        val folder = checkNotNull(context.getExternalFilesDir("communication-qa")).apply { mkdirs() }
        val ready = File(folder, "call-handoff-ready.json")
        val command = File(context.cacheDir, "call-handoff-command.json")
        val reportFile = File(folder, "call-handoff-results.json")
        command.delete()
        ready.delete()
        val report = JSONObject().put("scopeId", scope).put("role", role)
            .put("actualCallRepository", role == "receiver")
            .put("relayExperiment", relayExperiment)
        val phases = JSONArray()
        report.put("phases", phases)
        val connections = AtomicInteger()
        val sampling = AtomicBoolean(false)
        val maxFreshPacketGapMs = AtomicLong()
        val samplingFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var sampler: Thread? = null
        var socket: Socket? = null
        var callerManager: WebRTCManager? = null
        var repository: CallRepository? = null
        val auth = EntryPointAccessors.fromApplication(context, MessageNotificationReceiverEntryPoint::class.java).authService()
        val originalOwner = auth.getCurrentUserId()
        assertEquals("Use only the task-owned account; never change a personal session", receiver.getString("id"), originalOwner)
        try {
            compose.setContent { MaterialTheme { Text("Prueba de llamada: Wi-Fi y datos / $role") } }
            if (role == "caller") {
                val client = OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(20, TimeUnit.SECONDS).build()
                val signal = IO.socket("${SignalingConfig.BASE_URL}/call", IO.Options().apply {
                    this.auth = mapOf("token" to caller.getString("token"))
                    transports = arrayOf("websocket")
                    reconnection = true
                    reconnectionAttempts = 10
                    reconnectionDelay = 1_000
                    reconnectionDelayMax = 12_000
                    forceNew = true
                    callFactory = client
                    webSocketFactory = client
                })
                socket = signal
                val manager = WebRTCManager(context, object : SignalingEvents {
                    private fun send(event: String, target: String, field: String, value: JSONObject) {
                        signal.emit(event, JSONObject().put("target", target)
                            .put("userId", caller.getString("id")).put(field, value))
                    }
                    override fun onSendOffer(targetSocketId: String, offer: JSONObject) = send("offer", targetSocketId, "offer", offer)
                    override fun onSendAnswer(targetSocketId: String, answer: JSONObject) = send("answer", targetSocketId, "answer", answer)
                    override fun onSendIceCandidate(targetSocketId: String, candidate: JSONObject) = send("ice-candidate", targetSocketId, "candidate", candidate)
                }, accessTokenProvider = { caller.getString("token") },
                    iceTransportPolicy = if (relayExperiment) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL)
                callerManager = manager
                manager.setRemoteAudioPlaybackEnabled(true)
                fun joined(peer: JSONObject) {
                    if (peer.getString("userId") != caller.getString("id")) {
                        manager.onUserJoined(peer.getString("socketId"), peer.getString("userId"))
                    }
                }
                signal.on(Socket.EVENT_CONNECT) {
                    connections.incrementAndGet()
                    manager.setCurrentUser(caller.getString("id"), signal.id())
                    signal.emit("join-call", JSONObject().put("callId", scope)
                        .put("userId", caller.getString("id")).put("name", "QA red aislada"))
                }
                signal.on("user-joined") { values -> (values.firstOrNull() as? JSONObject)?.let(::joined) }
                signal.on("current-participants") { values -> (values.firstOrNull() as? JSONArray)?.let { peers ->
                    for (index in 0 until peers.length()) joined(peers.getJSONObject(index))
                } }
                signal.on("user-left") { values -> (values.firstOrNull() as? String)?.let(manager::onUserLeft) }
                signal.on("offer") { values -> (values.firstOrNull() as? JSONObject)?.let {
                    manager.onOfferReceived(it.getString("sender"), it.getString("userId"), it.getJSONObject("offer").getString("sdp"))
                } }
                signal.on("answer") { values -> (values.firstOrNull() as? JSONObject)?.let {
                    manager.onAnswerReceived(it.getString("sender"), it.getJSONObject("answer").getString("sdp"), it.getString("userId"))
                } }
                signal.on("ice-candidate") { values -> (values.firstOrNull() as? JSONObject)?.let {
                    manager.onIceCandidateReceived(it.getString("sender"), it.getJSONObject("candidate"), it.getString("userId"))
                } }
                signal.connect()
                await("Caller signaling unavailable", 25) { signal.connected() }
                ready.writeText(JSONObject().put("scopeId", scope).put("phase", "caller-ready").toString())
            } else {
                val repo = EntryPointAccessors.fromApplication(context, IncomingCallReceiverEntryPoint::class.java).callRepository()
                repository = repo
                assertNull("Do not interrupt another call", repo.activeCall.value)
                runBlocking { repo.acceptIncomingCall(CallInvite(callId = scope, title = "QA cambio de red",
                    callerUserId = caller.getString("id"), callerName = "QA red aislada", contextType = "dm")).getOrThrow() }
                repo.toggleSpeaker(true)
                if (relayExperiment) {
                    // This experiment is confined to the owned QA receiver. It is not
                    // evidence of a shipped policy until the production source is changed.
                    var ownedManager: WebRTCManager? = null
                    await("Owned receiver engine not initialized", 15) {
                        val field = CallRepository::class.java.getDeclaredField("_webRTCManager").apply { isAccessible = true }
                        ownedManager = (field.get(repo) as StateFlow<*>).value as? WebRTCManager
                        ownedManager != null
                    }
                    val policy = WebRTCManager::class.java.getDeclaredField("iceTransportPolicy").apply { isAccessible = true }
                    policy.set(checkNotNull(ownedManager), PeerConnection.IceTransportsType.RELAY)
                    assertEquals(PeerConnection.IceTransportsType.RELAY, policy.get(ownedManager))
                }
            }
            fun activeManager(): WebRTCManager? = callerManager ?: repository?.let { repo ->
                val field = CallRepository::class.java.getDeclaredField("_webRTCManager").apply { isAccessible = true }
                (field.get(repo) as StateFlow<*>).value as? WebRTCManager
            }
            await("No initial incoming audio", 40) { activeManager()?.let { audioCounters(it).values.sum() > 20 } == true }
            val originalCallStartedAt = repository?.activeCall?.value?.startedAtEpochMs
            sampling.set(true)
            sampler = Thread({
                try {
                    var previous = activeManager()?.let(::audioCounters).orEmpty()
                    var lastProgress = System.nanoTime()
                    while (sampling.get()) {
                        val current = activeManager()?.let(::audioCounters).orEmpty()
                        val now = System.nanoTime()
                        val progressed = current.any { (id, count) -> count > (previous[id] ?: 0L) }
                        val gap = (now - lastProgress) / 1_000_000
                        maxFreshPacketGapMs.updateAndGet { maxOf(it, gap) }
                        if (progressed) lastProgress = now
                        previous = current
                        Thread.sleep(200)
                    }
                } catch (failure: Throwable) {
                    samplingFailure.set(failure)
                }
            }, "owned-call-rtp-continuity").also { it.start() }
            assertEquals("Both endpoints must start on Wi-Fi", "wifi", transport())
            ready.writeText(JSONObject().put("scopeId", scope).put("phase", "baseline-ready").toString())
            for (phase in listOf("cellular", "wifi")) {
                await("Coordinator did not request $phase", 70) {
                    runCatching { JSONObject(command.readText()).let {
                        it.optString("scopeId") == scope && it.optString("phase") == phase
                    } }.getOrDefault(false)
                }
                val started = System.nanoTime()
                maxFreshPacketGapMs.set(0L)
                val expectedTransport = if (role == "receiver") phase else "wifi"
                await("Default network did not become $expectedTransport", 30) { transport() == expectedTransport }
                val networkReadyMs = (System.nanoTime() - started) / 1_000_000
                ready.writeText(JSONObject().put("scopeId", scope).put("phase", "$phase-network-ready").toString())
                await("Coordinator did not synchronize the new network", 35) {
                    runCatching { JSONObject(command.readText()).let {
                        it.optString("scopeId") == scope && it.optString("phase") == "$phase-measure"
                    } }.getOrDefault(false)
                }
                val bothNetworksReadyAt = System.nanoTime()
                var previous = activeManager()?.let(::audioCounters).orEmpty()
                var consecutive = 0
                var totalDelta = 0L
                await("Incoming audio did not resume on $role after $phase", 35) {
                    val current = activeManager()?.let(::audioCounters).orEmpty()
                    val delta = current.entries.sumOf { (id, value) ->
                        previous[id]?.let { (value - it).coerceAtLeast(0L) } ?: 0L
                    }
                    previous = current
                    if (delta > 0) { consecutive++; totalDelta += delta } else consecutive = 0
                    consecutive >= 8
                }
                val recoveredMs = (System.nanoTime() - started) / 1_000_000
                phases.put(JSONObject().put("phase", phase).put("transport", transport())
                    .put("incomingAudioResumedMs", recoveredMs).put("freshPacketsReceived", totalDelta)
                    .put("defaultNetworkReadyMs", networkReadyMs)
                    .put("networkReadyToFreshAudioMs", recoveredMs - networkReadyMs)
                    .put("bothNetworksReadyToFreshAudioMs", (System.nanoTime() - bothNetworksReadyAt) / 1_000_000)
                    .put("maxFreshRtpSamplingGapMs", maxFreshPacketGapMs.get())
                    .put("gapMetricScope", "200ms native RTP sampling throughout the handoff; not acoustic hardware measurement")
                    .put("consecutiveFreshSamples", consecutive))
                repository?.let { repo ->
                    assertEquals("A network handoff must preserve the call duration", originalCallStartedAt,
                        repo.activeCall.value?.startedAtEpochMs)
                }
                reportFile.writeText(report.toString(2))
                ready.writeText(JSONObject().put("scopeId", scope).put("phase", "$phase-recovered").toString())
                await("Coordinator did not verify both audio directions", 40) {
                    runCatching { JSONObject(command.readText()).let {
                        it.optString("scopeId") == scope && it.optString("phase") == "$phase-verified"
                    } }.getOrDefault(false)
                }
                // Keep the recovered route audible for the user's acoustic check.
                Thread.sleep(8_000)
            }
            assertEquals(originalOwner, auth.getCurrentUserId())
            assertNull("Continuous native RTP sampler failed", samplingFailure.get())
            report.put("ownerSessionPreserved", true).put("passed", true)
                .put("callerSignalingConnections", connections.get())
            reportFile.writeText(report.toString(2))
            ready.writeText(JSONObject().put("scopeId", scope).put("phase", "finished-ready").toString())
            await("Coordinator did not release both call endpoints", 40) {
                runCatching { JSONObject(command.readText()).let {
                    it.optString("scopeId") == scope && it.optString("phase") == "finished"
                } }.getOrDefault(false)
            }
        } finally {
            sampling.set(false)
            sampler?.join(4_000)
            assertTrue("RTP sampler did not stop", sampler?.isAlive != true)
            if (repository?.activeCall?.value?.callId == scope) runBlocking { repository?.hangup() }
            socket?.disconnect()
            socket?.off()
            callerManager?.let { manager ->
                val closed = CountDownLatch(1)
                manager.destroy { closed.countDown() }
                assertTrue("Caller cleanup timed out", closed.await(15, TimeUnit.SECONDS))
            }
            reportFile.writeText(report.toString(2))
        }
    }
}
