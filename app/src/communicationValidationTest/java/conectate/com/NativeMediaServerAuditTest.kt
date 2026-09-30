package conectate.com

import androidx.datastore.preferences.core.edit
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.signaling.SignalingConfig
import conectate.com.data.webrtc.SignalingEvents
import conectate.com.data.webrtc.WebRTCManager
import conectate.com.data.service.IncomingCallReceiverEntryPoint
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import conectate.com.data.model.CallConnectionState
import conectate.com.services.IncomingCallForegroundService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uses isolated accounts. The separate opt-in push test preserves the owner's session. */
@RunWith(AndroidJUnit4::class)
class NativeMediaServerAuditTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(20, TimeUnit.SECONDS).build()
    private val base get() = SignalingConfig.BASE_URL
    private data class Account(val id: String, val token: String)

    private fun request(path: String, account: Account? = null, method: String = "GET", body: JSONObject? = null, expected: Int = 200): JSONObject {
        val request = Request.Builder().url("$base$path").apply {
            account?.let { header("Authorization", "Bearer ${it.token}") }
            method(method, if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        }.build()
        return client.newCall(request).execute().use {
            assertEquals("HTTP $method $path", expected, it.code)
            val text = it.body?.string().orEmpty()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
    private fun register(): Account {
        val response = request("/v1/auth/register", method="POST", expected=201, body=JSONObject()
            .put("email", "media-qa.${UUID.randomUUID()}@example.invalid")
            .put("password", "QA-${UUID.randomUUID()}-only")
            .put("displayName", "Prueba multimedia temporal"))
        val user = response.getJSONObject("user")
        val id = user.optString("supabaseUserId").takeIf { it.isNotBlank() && it != "null" }
            ?: user.getString("id")
        UUID.fromString(id)
        return Account(id, response.getString("accessToken"))
    }
    private fun suppliedAccounts(): List<Account>? {
        if (InstrumentationRegistry.getArguments().getString("mediaFixtureAccounts") != "true") return null
        val file = File(context.cacheDir, "media-qa-accounts.json")
        val array = JSONArray(file.readText())
        assertEquals("Two isolated fixtures are required", 2, array.length())
        return (0..1).map { index -> array.getJSONObject(index).let { Account(it.getString("id"), it.getString("token")) } }
    }
    private fun waitUntil(message: String, seconds: Long = 25, predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < end) { if (predicate()) return; Thread.sleep(100) }
        assertTrue(message, predicate())
    }
    private inner class Endpoint(val account: Account, namespace: String, val relayOnly: Boolean) {
        val signalCounts=ConcurrentHashMap<String,AtomicInteger>()
        private fun count(key: String) { signalCounts.getOrPut(key) { AtomicInteger() }.incrementAndGet() }
        val socket: Socket = IO.socket("$base$namespace", IO.Options().apply {
            auth = mapOf("token" to account.token)
            transports = arrayOf("websocket"); reconnection=false; forceNew=true
            callFactory=client; webSocketFactory=client
        })
        val manager = WebRTCManager(context, object : SignalingEvents {
            private fun send(event: String, target: String, field: String, value: JSONObject) {
                count("sent-$event")
                val payload = JSONObject(value.toString())
                socket.emit(event, JSONObject().put("target",target).put("userId",account.id).put(field,payload))
            }
            override fun onSendOffer(targetSocketId: String, offer: JSONObject) = send("offer",targetSocketId,"offer",offer)
            override fun onSendAnswer(targetSocketId: String, answer: JSONObject) = send("answer",targetSocketId,"answer",answer)
            override fun onSendIceCandidate(targetSocketId: String, candidate: JSONObject) = send("ice-candidate",targetSocketId,"candidate",candidate)
        }, accessTokenProvider = { account.token },
            iceTransportPolicy = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL)
        init {
            // Avoid speaker feedback between two endpoints on the same handset.
            manager.setRemoteAudioPlaybackEnabled(false)
            socket.on("offer") { args -> (args.firstOrNull() as? JSONObject)?.let {
                count("received-offer")
                manager.onOfferReceived(it.getString("sender"), it.getString("userId"), it.getJSONObject("offer").getString("sdp"))
            } }
            socket.on("answer") { args -> (args.firstOrNull() as? JSONObject)?.let {
                count("received-answer")
                manager.onAnswerReceived(it.getString("sender"), it.getJSONObject("answer").getString("sdp"), it.getString("userId"))
            } }
            socket.on("ice-candidate") { args -> (args.firstOrNull() as? JSONObject)?.let {
                count("received-ice")
                manager.onIceCandidateReceived(it.getString("sender"), it.getJSONObject("candidate"), it.getString("userId"))
            } }
        }
        fun connect() {
            val connected = CountDownLatch(1)
            socket.once(Socket.EVENT_CONNECT) { connected.countDown() }
            socket.connect()
            assertTrue("Socket did not connect", connected.await(20,TimeUnit.SECONDS))
            manager.setCurrentUser(account.id, socket.id())
        }
        fun join(event: String, acknowledgment: String, payload: JSONObject) {
            val joined = CountDownLatch(1)
            socket.once(acknowledgment) { joined.countDown() }
            socket.emit(event,payload.put("userId",account.id).put("name","Prueba multimedia temporal"))
            assertTrue("Scope did not acknowledge $event", joined.await(15,TimeUnit.SECONDS))
        }
        @Suppress("UNCHECKED_CAST")
        fun stats(): RTCStatsReport {
            val field = WebRTCManager::class.java.getDeclaredField("peerConnections").apply { isAccessible=true }
            val peers = field.get(manager) as ConcurrentHashMap<String,PeerConnection>
            val pc = peers.values.first()
            val ready = CountDownLatch(1)
            var report: RTCStatsReport? = null
            pc.getStats { report=it; ready.countDown() }
            assertTrue("No media statistics",ready.await(5,TimeUnit.SECONDS))
            return checkNotNull(report)
        }
        fun close() {
            socket.disconnect(); socket.off()
            val closed=CountDownLatch(1)
            manager.destroy { closed.countDown() }
            assertTrue("Media engine cleanup timed out",closed.await(15,TimeUnit.SECONDS))
        }
        @Suppress("UNCHECKED_CAST")
        fun diagnostics(): JSONObject {
            val field=WebRTCManager::class.java.getDeclaredField("peerConnections").apply { isAccessible=true }
            val peers=field.get(manager) as ConcurrentHashMap<String,PeerConnection>
            fun mediaSdp(sdp: String?) = sdp.orEmpty().lines().filter {
                it.startsWith("m=") || it.startsWith("a=mid:") || it in listOf("a=sendrecv","a=sendonly","a=recvonly","a=inactive")
            }.joinToString("\n")
            return JSONObject().put("signals",JSONObject(signalCounts.mapValues { it.value.get() }))
                .put("ice",manager.peerConnectionStates.value.values.joinToString())
                .put("signaling",manager.signalingStates.value.values.joinToString())
                .put("debug",manager.debugInfo.value.values.joinToString())
                .put("peers",JSONArray(peers.values.map { pc -> JSONObject()
                    .put("localMedia",mediaSdp(pc.localDescription?.description))
                    .put("remoteMedia",mediaSdp(pc.remoteDescription?.description))
                    .put("transceivers",JSONArray(pc.transceivers.map { t -> JSONObject()
                        .put("mid",t.mid).put("kind",t.mediaType.toString()).put("direction",t.direction.toString())
                        .put("currentDirection",t.currentDirection?.toString()).put("sender",t.sender.track()?.kind())
                    }))
                }))
        }
    }
    /** Exercises the production guest path without switching or erasing the active account. */
    @Test(timeout=180_000) fun nativeGuestStartsWithLatestControlsAndKeepsTheOwnerSession() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditNativeGuest") == "true")
        val component = EntryPointAccessors.fromApplication(context, MessageNotificationReceiverEntryPoint::class.java)
        val auth = component.authService()
        val owner = checkNotNull(auth.getCurrentUserId())
        val provider = component.javaClass.getDeclaredField("liveRepositoryProvider").apply { isAccessible = true }.get(component)
            as javax.inject.Provider<*>
        val repository = provider.get() as conectate.com.data.repository.LiveRepository
        assertNull("Do not interrupt an existing LIVE", repository.activeSession.value)
        val fixtures = checkNotNull(suppliedAccounts())
        val host = Endpoint(fixtures.first(), "/live", false)
        var scopeId: String? = null
        var track: VideoTrack? = null
        val frames = AtomicInteger()
        val sink = VideoSink { frames.incrementAndGet() }
        val guestRequest = java.util.concurrent.atomic.AtomicReference<JSONObject?>(null)
        var guestLocalTrack: VideoTrack? = null
        val capturedFrames = AtomicInteger()
        var capturedWidth = 0; var capturedHeight = 0
        val localSink = VideoSink { frame -> capturedFrames.incrementAndGet(); capturedWidth = frame.buffer.width; capturedHeight = frame.buffer.height }
        compose.setContent { MaterialTheme { Text("Prueba de invitado LIVE; conserva tu cuenta y tus datos.") } }
        try {
            host.socket.on("viewer-joined") { args -> (args.firstOrNull() as? JSONObject)?.let {
                host.manager.onUserJoined(it.getString("socketId"), it.getString("userId"))
            } }
            host.socket.on("live-guest-request") { args -> (args.firstOrNull() as? JSONObject)?.let { guestRequest.set(it) } }
            host.connect()
            scopeId = request("/v1/data/live", host.account, "POST", JSONObject()
                .put("title", "Prueba aislada de arranque invitado").put("source", "audio"), 201)
                .getJSONObject("stream").getString("id")
            request("/v1/data/live/$scopeId/state", host.account, "PATCH", JSONObject().put("isActive", true).put("allowGuestRequests", true))
            host.join("join-live", "viewer-count", JSONObject().put("liveId", scopeId).put("role", "host")
                .put("source", "audio").put("videoEnabled", false).put("micEnabled", true))
            val joinedAt = System.nanoTime()
            runBlocking { repository.joinLiveAsViewer(scopeId).getOrThrow() }
            val joinMs = (System.nanoTime() - joinedAt) / 1_000_000
            val socketField = repository.javaClass.getDeclaredField("socket").apply { isAccessible = true }
            waitUntil("Guest socket did not connect", 30) { (socketField.get(repository) as? Socket)?.connected() == true }
            waitUntil("Viewer did not establish media before guest promotion", 30) {
                host.manager.peerConnectionStates.value.values.any {
                    it == PeerConnection.IceConnectionState.CONNECTED || it == PeerConnection.IceConnectionState.COMPLETED
                }
            }
            Thread.sleep(500)
            val requestedAt = System.nanoTime()
            repository.requestGuestAccess()
            waitUntil("Host received no persisted guest request", 30) { guestRequest.get() != null }
            val invitations = request("/v1/data/live/$scopeId/guests/invitations", host.account).getJSONArray("invitations")
            val invitation = (0 until invitations.length()).map { invitations.getJSONObject(it) }
                .first { it.optString("invitee_user_id").ifBlank { it.optString("inviteeUserId") } == owner && it.getString("status") == "pending" }
            request("/v1/data/live/$scopeId/guests/invitations/${invitation.getString("id")}", host.account,
                "PATCH", JSONObject().put("status", "accepted"))
            host.socket.emit("live-guest-response", JSONObject().put("liveId", scopeId)
                .put("targetSocketId", guestRequest.get()!!.getString("socketId")).put("accepted", true))
            waitUntil("Production guest never received acceptance", 20) {
                repository.guestAccessState.value.status == conectate.com.data.model.LiveGuestRequestStatus.ACCEPTED
            }
            val requestAndApprovalMs = (System.nanoTime() - requestedAt) / 1_000_000
            val offersBeforePromotion = host.signalCounts["sent-offer"]?.get() ?: 0
            host.manager.onUserJoined(guestRequest.get()!!.getString("socketId"), owner, restartExistingPeer = false)
            Thread.sleep(500)
            assertEquals("Guest promotion restarted healthy viewer ICE", offersBeforePromotion, host.signalCounts["sent-offer"]?.get() ?: 0)
            val startupAt = System.nanoTime()
            assertTrue(repository.startGuestBroadcast(videoEnabled = true, micEnabled = true))
            waitUntil("No native guest video track", 30) { host.manager.remoteVideoTracks.value.isNotEmpty() }
            track = host.manager.remoteVideoTracks.value.values.first().also { it.addSink(sink) }
            waitUntil("Guest camera sent no real picture", 30) { frames.get() > 0 }
            val firstFrameMs = (System.nanoTime() - startupAt) / 1_000_000
            assertTrue(repository.isGuestBroadcasting.value)
            guestLocalTrack = checkNotNull(repository.localVideoTrack.value).also { it.addSink(localSink) }
            val before = frames.get(); val beforeCapture = capturedFrames.get(); val sampledAt = System.nanoTime(); Thread.sleep(8_000)
            val elapsed = System.nanoTime() - sampledAt
            val fps = (frames.get() - before) * 1_000_000_000.0 / elapsed
            val captureFps = (capturedFrames.get() - beforeCapture) * 1_000_000_000.0 / elapsed
            File(context.getExternalFilesDir("communication-qa")!!, "native-guest-startup.json")
                .writeText(JSONObject().put("joinMs", joinMs).put("requestAndApprovalMs", requestAndApprovalMs)
                    .put("firstFrameMs", firstFrameMs).put("receivedFps", fps).put("captureFps", captureFps)
                    .put("capturedWidth", capturedWidth).put("capturedHeight", capturedHeight).put("passed", false).toString(2))
            assertTrue("Native guest camera is too slow: $fps", fps >= 8)
            assertTrue(repository.setGuestVideoEnabled(false))
            assertFalse(repository.isGuestVideoEnabled.value)
            assertTrue(repository.setGuestVideoEnabled(true))
            val resumedFrom = frames.get()
            waitUntil("Guest did not resume camera", 15) { frames.get() > resumedFrom + 3 }
            repository.leaveGuestSeat()
            repository.setGuestMicEnabled(true)
            assertFalse(repository.isGuestBroadcasting.value)
            // A delayed camera callback must not resurrect a seat that was left.
            Thread.sleep(2_000)
            assertFalse(repository.isGuestBroadcasting.value)
            val manager = repository.javaClass.getDeclaredField("webRTCManager").apply { isAccessible = true }
                .get(repository) as WebRTCManager
            val microphone = manager.javaClass.getDeclaredField("outgoingAudioEnabled").apply { isAccessible = true }.getBoolean(manager)
            assertFalse("A late mic toggle reopened a departed guest", microphone)
            assertEquals(owner, auth.getCurrentUserId())
            File(context.getExternalFilesDir("communication-qa")!!, "native-guest-startup.json")
                .writeText(JSONObject().put("joinMs", joinMs).put("requestAndApprovalMs", requestAndApprovalMs)
                    .put("firstFrameMs", firstFrameMs).put("receivedFps", fps).put("captureFps", captureFps)
                    .put("capturedWidth", capturedWidth).put("capturedHeight", capturedHeight).put("passed", true)
                    .put("cameraDisableEnable", true).put("seatStayedClosed", true).put("promotionPreservedMedia", true)
                    .put("ownerSessionPreserved", true).toString(2))
        } finally {
            track?.let { runCatching { it.removeSink(sink) } }
            guestLocalTrack?.let { runCatching { it.removeSink(localSink) } }
            if (repository.activeSession.value?.stream?.id == scopeId) runBlocking { repository.endLive().getOrThrow() }
            host.close()
            scopeId?.let { id ->
                request("/v1/data/live/$id/state", host.account, "PATCH", JSONObject().put("isActive", false))
                request("/v1/data/live/$id", host.account, "DELETE")
            }
            assertEquals("The guest audit changed the active account", owner, auth.getCurrentUserId())
        }
    }

    @Test(timeout=180_000) fun liveDeliversRealFramesThroughServerSignaling() = audit("live",false)
    @Test(timeout=180_000) fun voiceRoomDeliversVideoThroughServerSignaling() = audit("voice",false)
    @Test(timeout=180_000) fun callDeliversFramesUsingOnlyRelayCandidates() = audit("call",true)

    @Test(timeout=600_000) fun sustainedLiveKeepsDeliveringFramesAndRecordsMemory() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditMediaSoak") == "true")
        audit("live", false, sampleSeconds = 240)
    }

    /** Two-device run: a coordinator exchanges only socket IDs and creates the test scope. */
    @Test(timeout=180_000) fun mediaCrossesBetweenTwoSeparateDevices() {
        val args=InstrumentationRegistry.getArguments()
        val role=args.getString("crossDeviceRole")
        Assume.assumeTrue(role in listOf("publisher","viewer"))
        val flow=checkNotNull(args.getString("crossDeviceFlow"))
        val bothCameras = args.getString("crossDeviceBothCameras") == "true" && flow != "live"
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val transport = when {
            network?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            network?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            else -> "other"
        }
        args.getString("crossExpectedTransport")?.let { assertEquals("Network changed during audit", it, transport) }
        val scopeId=checkNotNull(args.getString("crossDeviceScope"))
        UUID.fromString(scopeId)
        compose.setContent { MaterialTheme { Text("Prueba entre dispositivos: $flow / $role") } }
        val fixtures=checkNotNull(suppliedAccounts())
        val endpoint=Endpoint(fixtures[if(role=="publisher") 0 else 1],when(flow) { "live"->"/live"; "call"->"/call"; else->"" },flow=="call")
        val peerFile=File(context.cacheDir,"media-qa-peer.json")
        val folder=context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        val readyFile=File(folder,"cross-device-ready.json")
        var remoteTrack: VideoTrack?=null
        val frames=AtomicInteger()
        val sink=VideoSink { frames.incrementAndGet() }
        try {
            peerFile.delete(); readyFile.delete()
            endpoint.connect()
            if(role=="publisher" || bothCameras) {
                val started=CountDownLatch(1); var ok=false
                endpoint.manager.startVideo(false,isFront=false,targetWidth=640,targetHeight=480,targetFps=24,onComplete={ok=it;started.countDown()})
                assertTrue(started.await(15,TimeUnit.SECONDS));assertTrue(ok)
            }
            when(flow) {
                "live" -> endpoint.join("join-live",if(role=="publisher") "viewer-count" else "host-info",JSONObject().put("liveId",scopeId).put("role",if(role=="publisher") "host" else "viewer"))
                "voice" -> {
                    request("/v1/data/voice/rooms/$scopeId/join",endpoint.account,"POST",JSONObject().put("userName","Prueba multimedia aislada").put("socketId",endpoint.socket.id()).put("isMuted",false))
                    endpoint.join("join-room","room-joined",JSONObject().put("roomId",scopeId))
                }
                "call" -> endpoint.join("join-call","call-joined",JSONObject().put("callId",scopeId))
                else -> error("Unsupported QA flow")
            }
            readyFile.writeText(JSONObject().put("scopeId",scopeId).put("socketId",endpoint.socket.id()).put("userId",endpoint.account.id).toString())
            waitUntil("Coordinator did not exchange peer socket IDs",60) { peerFile.isFile }
            val peer=JSONObject(peerFile.readText())
            val started=System.nanoTime()
            endpoint.manager.onUserJoined(peer.getString("socketId"),peer.getString("userId"))
            if(role=="viewer" || bothCameras) {
                waitUntil("No remote camera on the second device",40) { endpoint.manager.remoteVideoTracks.value.isNotEmpty() }
                remoteTrack=endpoint.manager.remoteVideoTracks.value.values.first().also { it.addSink(sink) }
                waitUntil("Second device received no picture",40) { frames.get()>0 }
            } else waitUntil("Second device did not connect",40) { endpoint.manager.peerConnectionStates.value.values.any { it in listOf(PeerConnection.IceConnectionState.CONNECTED,PeerConnection.IceConnectionState.COMPLETED) } }
            val firstFrameMs=(System.nanoTime()-started)/1_000_000
            val before=frames.get(); val sample=System.nanoTime(); Thread.sleep(15_000)
            val stats=endpoint.stats()
            val audio=stats.statsMap.values.any { it.type=="inbound-rtp" && it.members["kind"]=="audio" && ((it.members["packetsReceived"] as? Number)?.toLong() ?: 0)>0 }
            assertTrue("No incoming audio on $role",audio)
            val selectedId=stats.statsMap.values.firstOrNull { it.type=="transport" }?.members?.get("selectedCandidatePairId")?.toString()
            val pair=selectedId?.let(stats.statsMap::get)
            val types=listOf("localCandidateId","remoteCandidateId").mapNotNull { pair?.members?.get(it)?.toString()?.let(stats.statsMap::get)?.members?.get("candidateType") }
            val fps=(frames.get()-before)*1_000_000_000.0/(System.nanoTime()-sample)
            val report=JSONObject().put("scopeId",scopeId).put("flow",flow).put("role",role).put("transport",transport)
                .put("bidirectionalVideo",bothCameras).put("audioReceived",audio).put("receivedFps",fps).put("firstFrameMs",firstFrameMs)
                .put("candidateTypes",JSONArray(types)).put("rttSeconds",pair?.members?.get("currentRoundTripTime"))
            File(folder,"cross-device-$flow-$role.json").writeText(report.toString(2))
            if(role=="viewer" || bothCameras) assertTrue("Second-device video is too slow",fps>=8)
            if(flow=="call") assertTrue("Call must cross the public TURN server",types.contains("relay"))
        } finally {
            remoteTrack?.let { runCatching { it.removeSink(sink) } }
            endpoint.close();peerFile.delete();readyFile.delete()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test(timeout=120_000) fun publishedVideosRenderSeekAndResumeOnTheHandset() {
        Assume.assumeTrue("Published-video audit requires isolated fixture accounts",
            InstrumentationRegistry.getArguments().getString("mediaFixtureAccounts") == "true")
        compose.setContent { MaterialTheme { Text("Verificando reproducción de videos del servidor") } }
        val fixture=checkNotNull(suppliedAccounts()).first()
        val feed=request("/v1/data/videos?limit=10",fixture)
        val items=feed.optJSONArray("videos") ?: feed.optJSONArray("items") ?: error("No video feed")
        val urls=(0 until items.length()).map { items.getJSONObject(it) }
            .mapNotNull { video -> listOf("video_url","videoUrl","url").firstNotNullOfOrNull { key ->
                video.optString(key).takeIf { it.startsWith("https://") }
            } }.distinct().take(2)
        assertEquals("Two actual published videos are required",2,urls.size)
        val results=JSONArray()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for(url in urls) {
            var player: androidx.media3.exoplayer.ExoPlayer?=null
            var texture: android.graphics.SurfaceTexture?=null
            var surface: android.view.Surface?=null
            val rendered=CountDownLatch(1)
            val failure=java.util.concurrent.atomic.AtomicReference<androidx.media3.common.PlaybackException?>(null)
            val started=System.nanoTime()
            try {
                instrumentation.runOnMainSync {
                    texture=android.graphics.SurfaceTexture(false)
                    surface=android.view.Surface(texture)
                    val profile=conectate.com.util.VideoPerformanceProfiles.detect(context)
                    val loadControl=androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(profile.minBufferMs,profile.maxBufferMs,profile.playBufferMs,profile.rebufferMs)
                        .setPrioritizeTimeOverSizeThresholds(true).build()
                    player=androidx.media3.exoplayer.ExoPlayer.Builder(context).setLoadControl(loadControl)
                        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                            conectate.com.util.VideoCache.getCacheDataSourceFactory(context))).build().apply {
                            volume=0f
                            addListener(object: androidx.media3.common.Player.Listener {
                                override fun onRenderedFirstFrame() { rendered.countDown() }
                                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                    failure.set(error); rendered.countDown()
                                }
                            })
                            setVideoSurface(surface)
                            setMediaItem(conectate.com.util.UbuntuMedia.mediaItem(url))
                            prepare(); play()
                        }
                }
                assertTrue("Video never rendered",rendered.await(25,TimeUnit.SECONDS))
                assertNull("Video decoder/network error",failure.get())
                val firstFrameMs=(System.nanoTime()-started)/1_000_000
                var target=0L
                instrumentation.runOnMainSync {
                    val duration=checkNotNull(player).duration
                    assertTrue("Published video has no duration",duration>500)
                    target=(duration/2).coerceAtMost(5000)
                    player!!.pause(); player!!.seekTo(target)
                }
                waitUntil("Seeking did not reach the requested position",15) {
                    var ready=false
                    instrumentation.runOnMainSync { ready=player!!.playbackState==androidx.media3.common.Player.STATE_READY && kotlin.math.abs(player!!.currentPosition-target)<500 }
                    ready
                }
                instrumentation.runOnMainSync { player!!.play() }
                waitUntil("Video did not resume after seeking",10) {
                    var advanced=false
                    instrumentation.runOnMainSync { advanced=player!!.currentPosition>target+200 }
                    advanced
                }
                assertNull("Video failed after seeking",failure.get())
                results.put(JSONObject().put("firstFrameMs",firstFrameMs).put("seekAndResume",true))
            } finally {
                instrumentation.runOnMainSync { player?.release(); surface?.release(); texture?.release() }
            }
        }
        File(context.getExternalFilesDir("communication-qa")!!,"published-video-playback.json").writeText(results.toString(2))
    }

    /** Explicitly authorized handset only: delivers a real FCM call and answers its action. */
    @Test(timeout=120_000) fun incomingPushCanBeAnsweredOutsideTheApp() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditIncomingPush")=="true")
        val entry=EntryPointAccessors.fromApplication(context,MessageNotificationReceiverEntryPoint::class.java)
        val auth=entry.authService()
        val owner=checkNotNull(auth.getCurrentUserId()) { "An active handset account is required" }
        val repo=EntryPointAccessors.fromApplication(context,IncomingCallReceiverEntryPoint::class.java).callRepository()
        assertNull("Do not interrupt an existing personal call",repo.activeCall.value)
        val fixture=checkNotNull(suppliedAccounts()).first()
        val caller=Endpoint(fixture,"/call",false)
        val callId=UUID.randomUUID().toString()
        val notifications=context.getSystemService(NotificationManager::class.java)
        var notificationId: String?=null
        try {
            caller.connect()
            caller.socket.on("user-joined") { args -> (args.firstOrNull() as? JSONObject)?.let { peer ->
                caller.manager.onUserJoined(peer.getString("socketId"),peer.getString("userId"))
            } }
            caller.join("join-call","call-joined",JSONObject().put("callId",callId))
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            Thread.sleep(700)
            val sent=System.nanoTime()
            notificationId=request("/v1/data/notifications",fixture,"POST",JSONObject()
                .put("recipientId",owner).put("type","call_invite").put("targetId",callId)
                .put("content","Llamada de prueba autorizada").put("extraData",JSONObject()
                    .put("call_id",callId).put("call_context_type","dm").put("call_title","Prueba de llamada")
                    .put("call_created_at",java.time.Instant.now().toString()).put("call_is_video",false)),201)
                .getJSONObject("notification").getString("id")
            waitUntil("Real call push did not show a notification",30) {
                notifications.activeNotifications.any { it.id==callId.hashCode() }
            }
            val notification=notifications.activeNotifications.first { it.id==callId.hashCode() }.notification
            val visibleMs=(System.nanoTime()-sent)/1_000_000
            val answer=notification.actions.first { action -> listOf("Responder","Contestar","Answer").any {
                action.title.toString().contains(it,ignoreCase=true)
            } }
            var answered=0L
            assertNotNull(answer.actionIntent)
            // Android 14 can block an app sending its own activity PendingIntent
            // while in the background. Click the actual SystemUI notification,
            // as the user does, to exercise the platform's authorized launch.
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            val info=automation.serviceInfo
            val previousFlags=info.flags
            info.flags=previousFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo=info
            try {
                automation.executeShellCommand("cmd statusbar expand-notifications").close()
                Thread.sleep(1000)
                fun answerNode(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    if(node==null) return null
                    val labels=listOfNotNull(node.text?.toString(),node.contentDescription?.toString())
                    if(labels.any { label -> listOf("Responder","Contestar","Answer").any { label.contains(it,true) } }) return node
                    for(index in 0 until node.childCount) answerNode(node.getChild(index))?.let { return it }
                    return null
                }
                var button: android.view.accessibility.AccessibilityNodeInfo?=null
                waitUntil("No visible answer action in SystemUI",10) {
                    button=automation.windows.firstNotNullOfOrNull { answerNode(it.root) }
                    button!=null
                }
                assertNotNull("No visible answer action in SystemUI",button)
                var clickable=button
                while(clickable!=null && !clickable.isClickable) clickable=clickable.parent
                answered=System.nanoTime()
                assertTrue("SystemUI could not activate the answer button",clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true)
            } finally {
                info.flags=previousFlags;automation.serviceInfo=info
            }
            waitUntil("Answer did not connect the actual call repository",40) {
                repo.activeCall.value?.let { it.callId==callId && it.connectionState==CallConnectionState.CONNECTED }==true
            }
            val report=JSONObject().put("pushToNotificationMs",visibleMs)
                .put("answerToConnectedMs",(System.nanoTime()-answered)/1_000_000)
                .put("callConnected",true).put("ownerSessionPreserved",auth.getCurrentUserId()==owner)
            File(context.getExternalFilesDir("communication-qa")!!,"incoming-real-push.json").writeText(report.toString(2))
            assertEquals(owner,auth.getCurrentUserId())
        } finally {
            if(repo.activeCall.value?.callId==callId) runBlocking { repo.hangup() }
            notifications.cancel(callId.hashCode())
            IncomingCallForegroundService.stop(context)
            notificationId?.let { id -> auth.getAccessToken()?.let { token ->
                runCatching { request("/v1/data/notifications/$id",Account(owner,token),"DELETE") }
            } }
            caller.close()
            Thread.sleep(2_000)
            assertNull("Delayed audio work reopened a hung-up call",repo.activeCall.value)
            val managerField=repo.javaClass.getDeclaredField("_webRTCManager").apply { isAccessible=true }
            val manager=(managerField.get(repo) as kotlinx.coroutines.flow.StateFlow<*>).value as? WebRTCManager
            assertTrue("Delayed work recreated peers after hangup",manager?.peerConnectionStates?.value.isNullOrEmpty())
        }
    }

    @Test(timeout=180_000) fun screenShareDeliversChangingFramesThroughTheVoiceServer() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditScreenShare")=="true")
        audit("voice",false,isScreen=true)
    }

    @Test(timeout=30_000) fun historyIsIsolatedAndOnlyAuditArtifactsAreCleaned() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditCleanup")=="true")
        val auth=EntryPointAccessors.fromApplication(context,MessageNotificationReceiverEntryPoint::class.java).authService()
        val owner=checkNotNull(auth.getCurrentUserId())
        val fixtures=checkNotNull(suppliedAccounts())
        val isolatedPeers=fixtures.map { it.id }.toSet()
        val repo=EntryPointAccessors.fromApplication(context,IncomingCallReceiverEntryPoint::class.java).callRepository()
        assertNull("An active call must not be cleaned",repo.activeCall.value)
        val field=repo.javaClass.getDeclaredField("callHistoryRepository").apply { isAccessible=true }
        val history=field.get(repo) as conectate.com.data.repository.CallHistoryRepository
        val entry=conectate.com.data.model.CallHistoryEntry(callId=UUID.randomUUID().toString(),
            title="Prueba de aislamiento temporal",subtitle="QA",contextType="dm",statusLabel="Finalizada",peerUserId=fixtures.first().id)
        runBlocking {
            history.record(entry,ownerId=fixtures.first().id)
            val visible=history.entries.first()
            assertFalse("Another account's history was exposed",visible.any { it.id==entry.id })
            history.record(entry.copy(id=UUID.randomUUID().toString()),ownerId=owner)
            assertTrue("Owner's history was not visible",history.entries.first().any { it.callId==entry.callId })
        }
        val accessor=Class.forName("conectate.com.data.repository.CallHistoryRepositoryKt").declaredMethods
            .first { it.name.contains("getCallHistoryDataStore") && it.parameterCount==1 }.apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val store=accessor.invoke(null,context) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        val serializer=kotlinx.serialization.builtins.ListSerializer(conectate.com.data.model.CallHistoryEntry.serializer())
        val codec=kotlinx.serialization.json.Json { ignoreUnknownKeys=true;encodeDefaults=true }
        runBlocking { store.edit { prefs ->
            prefs.asMap().keys.filter { it.name=="entries_json" || it.name.startsWith("entries_json:") }.forEach { key ->
                val stringKey=androidx.datastore.preferences.core.stringPreferencesKey(key.name)
                val entries=runCatching { codec.decodeFromString(serializer,prefs[stringKey].orEmpty()) }.getOrNull() ?: return@forEach
                val kept=entries.filterNot { it.peerUserId in isolatedPeers }
                if(kept.size!=entries.size) prefs[stringKey]=codec.encodeToString(serializer,kept)
            }
        } }
        assertEquals("Cleaning changed the owner session",owner,auth.getCurrentUserId())
        runBlocking { assertFalse(history.entries.first().any { it.peerUserId in isolatedPeers }) }
    }

    private fun confirmProjectionConsent() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val info=automation.serviceInfo;val oldFlags=info.flags
        info.flags=oldFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo=info
        try {
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if(node==null) return null
                val labels=listOfNotNull(node.text?.toString(),node.contentDescription?.toString())
                if(node.packageName?.toString()=="com.android.systemui" && (node.viewIdResourceName=="android:id/button1" || labels.any { it.lowercase() in listOf("iniciar ahora","empezar ahora","comenzar ahora","start now","start recording","comenzar","iniciar","start") })) return node
                for(i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            var button: android.view.accessibility.AccessibilityNodeInfo?=null
            waitUntil("No system consent action for the authorized screen audit",15) {
                button=automation.windows.firstNotNullOfOrNull { find(it.root) };button!=null
            }
            var clickable=button
            while(clickable!=null && !clickable.isClickable) clickable=clickable.parent
            assertTrue("Could not confirm screen capture",clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true)
        } finally { info.flags=oldFlags;automation.serviceInfo=info }
    }

    private fun audit(flow: String, relayOnly: Boolean, isScreen: Boolean=false, sampleSeconds: Int=8) {
        for (permission in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)) {
            assertEquals("Grant $permission before this audit", PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context,permission))
        }
        val projection=java.util.concurrent.atomic.AtomicReference<android.content.Intent?>(null)
        if(isScreen) compose.mainClock.autoAdvance=false
        compose.setContent { MaterialTheme {
            if(isScreen) MediaProjectionAuditPreview { projection.set(it) }
            else Text("Prueba aislada de $flow. No modifica tus cuentas.")
        } }
        if(isScreen) {
            confirmProjectionConsent()
            waitUntil("Screen consent returned no capture permission",15) { projection.get()!=null }
            ContextCompat.startForegroundService(context,android.content.Intent(context,conectate.com.services.VoiceForegroundService::class.java)
                .setAction(conectate.com.services.VoiceForegroundService.ACTION_START)
                .putExtra(conectate.com.services.VoiceForegroundService.EXTRA_ROOM_NAME,"Prueba aislada de pantalla")
                .putExtra(conectate.com.services.VoiceForegroundService.EXTRA_VIDEO_MODE,"screen"))
            Thread.sleep(500)
        }
        val accounts= mutableListOf<Account>()
        val fixtures=suppliedAccounts()
        var first: Endpoint? = null; var second: Endpoint? = null
        var scopeId: String? = null
        var track: VideoTrack? = null
        var localTrack: VideoTrack? = null
        val frames=AtomicInteger()
        val capturedFrames=AtomicInteger()
        val lumaValues=java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val sink=VideoSink { frame ->
            frames.incrementAndGet()
            if(isScreen && frames.get()%5==0) frame.buffer.toI420()?.let { buffer ->
                try { lumaValues.add(buffer.dataY.get((buffer.height/2)*buffer.strideY+buffer.width/2).toInt() and 255) }
                finally { buffer.release() }
            }
        }
        val localSink=VideoSink { capturedFrames.incrementAndGet() }
        try {
            if (fixtures != null) accounts.addAll(fixtures)
            else { accounts += register(); accounts += register() }
            val namespace=when(flow) { "live" -> "/live"; "call" -> "/call"; else -> "" }
            first=Endpoint(accounts[0],namespace,relayOnly); second=Endpoint(accounts[1],namespace,relayOnly)
            first.connect(); second.connect()
            val captureStarted=CountDownLatch(1); var captureOk=false
            first.manager.startVideo(isScreen,intent=projection.get(),isFront=false,targetWidth=640,targetHeight=480,targetFps=if(isScreen) 15 else 24,
                onComplete={ captureOk=it; captureStarted.countDown() })
            assertTrue("Camera start timed out",captureStarted.await(15,TimeUnit.SECONDS)); assertTrue("Camera did not start",captureOk)
            localTrack=first.manager.localVideoTrack.value
            localTrack?.addSink(localSink)
            if (flow=="live") {
                scopeId=request("/v1/data/live",accounts[0],"POST",JSONObject().put("title","Prueba temporal de medios").put("source","camera"),201).getJSONObject("stream").getString("id")
                first.join("join-live","viewer-count",JSONObject().put("liveId",scopeId).put("role","host").put("source","camera").put("videoEnabled",true).put("micEnabled",true))
                second.join("join-live","host-info",JSONObject().put("liveId",scopeId).put("role","viewer"))
            } else if(flow=="voice") {
                scopeId=UUID.randomUUID().toString()
                request("/v1/data/voice/rooms",accounts[0],"POST",JSONObject().put("id",scopeId).put("name","Prueba temporal de medios").put("roomMode","casual").put("isPublic",true),201)
                for (endpoint in listOf(first,second)) {
                    request("/v1/data/voice/rooms/$scopeId/join",endpoint.account,"POST",JSONObject().put("userName","Prueba temporal de medios").put("socketId",endpoint.socket.id()).put("isMuted",false))
                    endpoint.join("join-room","room-joined",JSONObject().put("roomId",scopeId))
                }
            } else {
                scopeId=UUID.randomUUID().toString()
                first.join("join-call","call-joined",JSONObject().put("callId",scopeId))
                second.join("join-call","call-joined",JSONObject().put("callId",scopeId))
            }
            val mediaStarted=System.nanoTime()
            first.manager.onUserJoined(second.socket.id(),second.account.id)
            second.manager.onUserJoined(first.socket.id(),first.account.id)
            waitUntil("No remote video track") { second.manager.remoteVideoTracks.value.isNotEmpty() }
            track=second.manager.remoteVideoTracks.value.values.first(); track.addSink(sink)
            waitUntil("Remote video stayed black") { frames.get()>0 }
            val firstFrameMs=(System.nanoTime()-mediaStarted)/1_000_000
            val outboundBytesAtStart = first.stats().statsMap.values.filter { it.type == "outbound-rtp" }
                .sumOf { (it.members["bytesSent"] as? Number)?.toLong() ?: 0L }
            val before=frames.get(); val beforeCaptured=capturedFrames.get(); val started=System.nanoTime()
            val memorySamples = JSONArray()
            val cpuStarted = android.os.Process.getElapsedCpuTime()
            val pssStarted = android.os.Debug.getPss()
            if(isScreen) repeat(80) { compose.mainClock.advanceTimeBy(100);Thread.sleep(100) }
            else {
                var remaining = sampleSeconds
                while (remaining > 0) {
                    val interval = minOf(15, remaining)
                    val previousFrames = frames.get()
                    Thread.sleep(interval * 1_000L)
                    assertTrue("Video stopped during sustained sampling", frames.get() > previousFrames)
                    memorySamples.put(JSONObject().put("elapsedSeconds", sampleSeconds - remaining + interval)
                        .put("pssKb", android.os.Debug.getPss()).put("frames", frames.get() - before))
                    remaining -= interval
                }
            }
            val fps=(frames.get()-before)*1_000_000_000.0/(System.nanoTime()-started)
            val capturedFps=(capturedFrames.get()-beforeCaptured)*1_000_000_000.0/(System.nanoTime()-started)
            val stats=second.stats()
            val inbound=stats.statsMap.values.filter { it.type=="inbound-rtp" }
            assertTrue("No received audio RTP",inbound.any { it.members["kind"]=="audio" && (it.members["packetsReceived"] as? Number)?.toLong()?.let { n->n>0 }==true })
            val publisherStats = first.stats()
            val firstInbound=publisherStats.statsMap.values.filter { it.type=="inbound-rtp" }
            val outboundBytes = publisherStats.statsMap.values.filter { it.type == "outbound-rtp" }
                .sumOf { (it.members["bytesSent"] as? Number)?.toLong() ?: 0L } - outboundBytesAtStart
            // Android may silence the second concurrent microphone on one handset.
            // The separate-device audit requires received audio on BOTH devices.
            val returnAudioRtp=firstInbound.any { it.members["kind"]=="audio" && (it.members["packetsReceived"] as? Number)?.toLong()?.let { n->n>0 }==true }
            val selectedId=stats.statsMap.values.firstOrNull { it.type=="transport" }
                ?.members?.get("selectedCandidatePairId")?.toString()
            val selected=selectedId?.let(stats.statsMap::get) ?: stats.statsMap.values.firstOrNull {
                it.type=="candidate-pair" && it.members["state"]=="succeeded" && it.members["nominated"]==true
            }
            val candidates=listOf("localCandidateId","remoteCandidateId").mapNotNull { selected?.members?.get(it)?.toString()?.let(stats.statsMap::get)?.members?.get("candidateType") }
            val report=JSONObject().put("flow",flow).put("relayOnly",relayOnly).put("firstFrameMs",firstFrameMs).put("receivedFps",fps).put("frames",frames.get())
                .put("captureFps",capturedFps).put("candidateTypes",JSONArray(candidates)).put("rttSeconds",selected?.members?.get("currentRoundTripTime"))
                .put("sampleSeconds", sampleSeconds).put("memorySamples", memorySamples)
                .put("pssGrowthKb", android.os.Debug.getPss() - pssStarted)
                .put("processCpuMs", android.os.Process.getElapsedCpuTime() - cpuStarted)
                .put("publisherRtpBytes", outboundBytes).put("publisherRtpKbps", outboundBytes * 8.0 / sampleSeconds / 1000)
                .put("physicalBatteryMeasured", false)
            if (InstrumentationRegistry.getArguments().getString("auditNetworkRecovery") == "true") {
                val originalTrack = first.manager.localVideoTrack.value
                first.manager.setAudioEnabled(false)
                val recoveryFrom = frames.get()
                val recoveryAt = System.nanoTime()
                first.manager.recoverAfterNetworkChange("qa-recovery")
                waitUntil("ICE recovery did not deliver new video frames", 35) {
                    frames.get() > recoveryFrom + 20 && first.manager.peerConnectionStates.value.values.any {
                        it == PeerConnection.IceConnectionState.CONNECTED || it == PeerConnection.IceConnectionState.COMPLETED
                    }
                }
                assertSame("Recovery replaced the camera", originalTrack, first.manager.localVideoTrack.value)
                val muteField = WebRTCManager::class.java.getDeclaredField("outgoingAudioEnabled").apply { isAccessible = true }
                assertFalse("Recovery reset the mute choice", muteField.getBoolean(first.manager))
                first.manager.setAudioEnabled(true)
                report.put("iceRecoveryMs", (System.nanoTime() - recoveryAt) / 1_000_000)
                    .put("recoveryPreservedCameraAndMute", true)
                    .put("physicalNetworkHandoffTested", false)
            }
            first.manager.setVideoEnabled(false)
            Thread.sleep(500)
            first.manager.setVideoEnabled(true)
            val resumedFrom=frames.get()
            if(isScreen) {
                // The test owns Compose's animation clock. Keep changing pixels
                // after resume; a static screen is not guaranteed a new frame.
                val resumeDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                while(frames.get()<=resumedFrom+3 && System.nanoTime()<resumeDeadline) {
                    compose.mainClock.advanceTimeBy(100);Thread.sleep(100)
                }
                assertTrue("Screen did not resume with changing pixels",frames.get()>resumedFrom+3)
            } else waitUntil("Video did not resume after enabling the camera",10) { frames.get()>resumedFrom+3 }
            if (!isScreen) {
            val switched=CountDownLatch(1); var switchOk=false
            assertTrue("Camera cannot switch",first.manager.switchCamera { switchOk=it; switched.countDown() })
            assertTrue("Camera switch timed out",switched.await(10,TimeUnit.SECONDS))
            assertTrue("Camera switch failed",switchOk)
            val switchedFrom=frames.get()
            waitUntil("Remote camera stayed black after switching",10) { frames.get()>switchedFrom+3 }
            }
            if(isScreen) assertTrue("Shared screen did not show the changing test image: ${lumaValues.size} distinct values",lumaValues.size>=3)
            report.put("cameraDisableEnable",true).put("cameraSwitch",!isScreen).put("audioBothDirections",returnAudioRtp).put("screenShare",isScreen).put("differentScreenLuma",lumaValues.size)
            val folder=context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
            File(folder,if(isScreen) "media-screen-share.json" else "media-$flow.json").writeText(report.toString(2))
            Log.i("NativeMediaAudit",report.toString())
            if(relayOnly) assertTrue("Relay path was not selected",candidates.contains("relay"))
            assertTrue("Video is too slow: $fps FPS",fps>=8.0)
        } finally {
            val diagnostics=JSONObject().put("flow",flow).put("first",first?.diagnostics()).put("second",second?.diagnostics())
                .put("capturedFrames",capturedFrames.get()).put("receivedFrames",frames.get())
            File(context.getExternalFilesDir("communication-qa")!!,"media-$flow-diagnostics.json").writeText(diagnostics.toString(2))
            track?.let { runCatching { it.removeSink(sink) } }
            localTrack?.let { runCatching { it.removeSink(localSink) } }
            second?.close(); first?.close()
            if(scopeId!=null && accounts.isNotEmpty()) runCatching {
                if(flow=="live") request("/v1/data/live/$scopeId",accounts[0],"DELETE")
                if(flow=="voice") request("/v1/data/voice/rooms/$scopeId",accounts[0],"DELETE")
            }
            if (fixtures == null) accounts.reversed().forEach { runCatching { request("/rest/v1/rpc/delete_own_account",it,"POST",JSONObject()) } }
            if(isScreen) context.stopService(android.content.Intent(context,conectate.com.services.VoiceForegroundService::class.java))
            Thread.sleep(1_000)
        }
    }
}
