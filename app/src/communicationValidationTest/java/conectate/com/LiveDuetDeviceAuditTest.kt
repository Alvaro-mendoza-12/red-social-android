package conectate.com

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.livekit.LiveKitRoomBridge
import conectate.com.data.model.LiveSfuSession
import conectate.com.data.signaling.SignalingConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import io.livekit.android.events.collect
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
                // Wall-clock sleeps do not advance Compose's test frame clock.
                // Keep renderer replacement running during native network waits.
                compose.waitForIdle()
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
        val drawnFrames = AtomicInteger()
        val frameTimes = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val rendered = CountDownLatch(1)
        var renderer: io.livekit.android.renderer.TextureViewRenderer? = null
        var video: LiveKitRoomBridge.RemoteVideo? = null
        val shown = mutableStateOf(true)
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val roomEvents = java.util.concurrent.ConcurrentLinkedQueue<String>()
        try {
            existingCapture?.let { manager ->
                val ready = CountDownLatch(1)
                var success = false
                manager.startVideo(false, isFront = true, targetWidth = 640, targetHeight = 360,
                    targetFps = 60, onComplete = { success = it; ready.countDown() })
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
            fun probeStoppedRenderer(): JSONObject {
                val current = bridge.remoteVideos.value.firstOrNull()
                val publication = room.remoteParticipants.values.flatMap { it.trackPublications.values }
                    .filterIsInstance<io.livekit.android.room.track.RemoteTrackPublication>()
                    .firstOrNull { it.track === current?.track }
                val before = frames.get()
                val probe = JSONObject()
                    .put("remoteVideos", bridge.remoteVideos.value.size)
                    .put("rendererAttached", renderer?.isAttachedToWindow == true)
                    .put("trackEnabled", current?.track?.enabled == true)
                    .put("publicationSubscribed", publication?.subscribed == true)
                    .put("publicationMuted", publication?.muted == true)
                    .put("visibleTrackMatchesCurrent", video?.track === current?.track)
                    .put("bindingVersions", JSONArray(bridge.remoteVideos.value.map { it.bindingVersion }))
                    .put("roomEvents", JSONArray(roomEvents.toList()))
                // Diagnostic only: the original acceptance result stays failed.
                // Reassert the public SDK's desired enabled state for an owned
                // publication, then see whether new video reaches the renderer.
                publication?.setEnabled(false)
                publication?.setEnabled(true)
                Thread.sleep(3_000)
                probe.put("framesAfterEnabledRefresh", frames.get() - before)
                if (frames.get() == before && current != null && renderer != null) {
                    val view = renderer!!
                    probe.put("sinkRebound", bridge.rebindRenderer(current, view))
                    Thread.sleep(3_000)
                    probe.put("framesAfterSinkRebind", frames.get() - before)
                }
                if (frames.get() == before && publication != null) {
                    publication.setSubscribed(false)
                    val detachedBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
                    while (publication.track != null && System.nanoTime() < detachedBy) Thread.sleep(50)
                    probe.put("unsubscriptionAcknowledged", publication.track == null)
                    publication.setSubscribed(true)
                    Thread.sleep(4_000)
                    probe.put("framesAfterResubscribe", frames.get() - before)
                }
                return probe
            }
            eventScope.launch { room.events.collect {
                roomEvents.add(it.javaClass.simpleName)
                while (roomEvents.size > 80) roomEvents.poll()
            } }
            val sources = room.localParticipant.trackPublications.values.map { it.source }.toSet()
            assertTrue("Parallel startup lost microphone publication", io.livekit.android.room.track.Track.Source.MICROPHONE in sources)
            assertTrue("Parallel startup lost camera publication", io.livekit.android.room.track.Track.Source.CAMERA in sources)
            File(folder, "sfu-published-$role.json").writeText(JSONObject()
                .put("scopeId", liveId).put("sessionRequestMs", sessionRequestMs)
                .put("connectMs", connectMs).toString())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (bridge.remoteVideos.value.isEmpty() && System.nanoTime() < deadline) Thread.sleep(100)
            assertTrue("No guest/host video track", bridge.remoteVideos.value.isNotEmpty())
            // Observe publication removal/restoration just like the actual LIVE
            // surface. A retained renderer of a muted track is an invalid probe.
            compose.setContent {
                val currentVideo=bridge.remoteVideos.collectAsState().value.firstOrNull()
                MaterialTheme { if(shown.value && currentVideo!=null) key(currentVideo.track, currentVideo.bindingVersion) {
                    AndroidView(factory = {
                        object : io.livekit.android.renderer.TextureViewRenderer(context) {
                            override fun onFrame(frame: livekit.org.webrtc.VideoFrame) {
                                frameTimes.add(System.nanoTime())
                                frames.incrementAndGet();super.onFrame(frame)
                            }
                            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                                drawnFrames.incrementAndGet()
                                super.onSurfaceTextureUpdated(surface)
                            }
                        }.also { view ->
                            renderer=view;video=currentVideo
                            assertTrue(bridge.attachRenderer(currentVideo,view))
                            view.addFrameListener({ bitmap -> bitmap?.recycle();rendered.countDown() },0.1f)
                        }
                    },onRelease={ view ->
                        bridge.detachRenderer(currentVideo,view)
                        if(renderer===view) { renderer=null;video=null }
                    },modifier=Modifier.fillMaxSize())
                } }
            }
            assertTrue("No decoded/drawn duet image", rendered.await(20, TimeUnit.SECONDS))
            val firstFrameMs = (System.nanoTime() - started) / 1_000_000
            File(folder,"sfu-ready-$role.json").writeText(JSONObject().put("scopeId",liveId).toString())
            assertTrue("Coordinator did not start both media samples",waitForPhase("sfu-sample-now.json",40))
            val existingBefore = existingFrames.get()
            val sampleMs = (arguments.getString("sampleDurationMs")?.toLongOrNull() ?: 10_000L).coerceIn(10_000L, 60_000L)
            val before = frames.get(); val sample = System.nanoTime()
            while (System.nanoTime() - sample < TimeUnit.MILLISECONDS.toNanos(sampleMs)) {
                compose.waitForIdle()
                Thread.sleep(50)
            }
            val sampleEnd = System.nanoTime()
            val fps = (frames.get() - before) * 1_000_000_000.0 / (sampleEnd - sample)
            val existingCaptureFps = (existingFrames.get() - existingBefore) * 1_000_000_000.0 / (sampleEnd - sample)
            val sampleFrames = frameTimes.filter { it in sample..sampleEnd }.sorted()
            val maximumFrameGapMs = (listOf(sample) + sampleFrames + sampleEnd).zipWithNext { a, b -> b - a }
                .maxOrNull()!!.toDouble() / 1_000_000.0
            val perSecondFrames = (0 until (sampleMs / 1_000).toInt()).map { second ->
                sampleFrames.count { (it - sample) / 1_000_000_000L == second.toLong() }
            }
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
            val videoFields = listOf("framesPerSecond", "framesEncoded", "framesDecoded", "framesDropped",
                "frameWidth", "frameHeight", "totalEncodeTime", "totalDecodeTime", "encoderImplementation",
                "decoderImplementation", "qualityLimitationReason", "freezeCount", "totalFreezesDuration",
                "packetsLost", "jitter", "jitterBufferDelay", "jitterBufferEmittedCount", "jitterBufferTargetDelay",
                "keyFramesEncoded", "keyFramesDecoded", "nackCount", "pliCount", "firCount",
                "retransmittedPacketsSent", "retransmittedBytesSent", "totalProcessingDelay")
            val videoDetails = JSONArray(incomingStats.filter {
                it.type in listOf("inbound-rtp", "outbound-rtp") && it.members["kind"] == "video"
            }.map { stat -> JSONObject().put("direction", stat.type).apply {
                videoFields.forEach { field -> stat.members[field]?.let { put(field, it) } }
                stat.members["codecId"]?.toString()?.let { id -> incomingStats.firstOrNull { it.id == id } }
                    ?.members?.get("mimeType")?.let { put("codec", it) }
            } })
            val connections = JSONArray(incomingStats.filter {
                it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
            }.map { pair -> JSONObject().apply {
                listOf("currentRoundTripTime", "availableOutgoingBitrate", "bytesSent", "bytesReceived")
                    .forEach { key -> pair.members[key]?.let { put(key, it) } }
                listOf("localCandidateId", "remoteCandidateId").forEach { key ->
                    val candidate = incomingStats.firstOrNull { it.id == pair.members[key] }
                    candidate?.members?.get("candidateType")?.let { put(key.removeSuffix("Id") + "Type", it) }
                    candidate?.members?.get("protocol")?.let { put(key.removeSuffix("Id") + "Protocol", it) }
                }
            } })
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "sfu-diagnostics-$role.json")
                .writeText(JSONObject().put("scopeId",liveId).put("firstFrameMs",firstFrameMs)
                    .put("connectMs",connectMs).put("sessionRequestMs",sessionRequestMs)
                    .put("receivedFps",fps).put("audioReceived",audio).put("existingCaptureFps", existingCaptureFps)
                    .put("sampleDurationMs", sampleMs).put("maximumFrameGapMs", maximumFrameGapMs)
                    .put("perSecondFrames", JSONArray(perSecondFrames))
                    .put("publishedTracks",room.localParticipant.trackPublications.values.map { it.kind.toString()+":"+it.muted })
                    .put("inbound",statsDetails).put("videoStats", videoDetails).put("connections", connections).toString(2))
            assertTrue("No incoming audio on $role", audio)
            val minimumRemoteFps = arguments.getString("minimumRemoteFps")?.toDoubleOrNull() ?: 8.0
            assertTrue("Duet image is too slow: $fps < $minimumRemoteFps", fps >= minimumRemoteFps)
            arguments.getString("maximumFrameGapMs")?.toDoubleOrNull()?.let { maximum ->
                assertTrue("Duet image stalled: $maximumFrameGapMs ms > $maximum ms", maximumFrameGapMs <= maximum)
            }
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
                        targetFps = 60, onComplete = { started = it; ready.countDown() })
                    assertTrue("Replacement capture timed out", ready.await(15, TimeUnit.SECONDS))
                    assertTrue("Replacement capture failed", started)
                    val replacementBefore = sharedFrames.get()
                    bridge.updateMediaEnabled(true, true)
                    assertTrue("Collaboration controls did not resume both publications", waitForCamera(false))
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while(sharedFrames.get() - replacementBefore < 5 && System.nanoTime() < until) Thread.sleep(100)
                    assertTrue("Shared capture did not follow the replacement native track", sharedFrames.get() - replacementBefore >= 5)
                } finally {
                    localTrack.removeRenderer(sharedSink)
                }
            }
            if (arguments.getString("auditNetworkRecovery") == "true") {
                File(folder,"sfu-recovery-ready-$role.json").writeText(JSONObject().put("scopeId",liveId).toString())
                assertTrue("Coordinator did not synchronize camera-control verification",waitForPhase("sfu-before-outage-check.json",45))
                val controlsRecoveryStarted = System.nanoTime()
                val resumeBefore = frames.get()
                while (frames.get() - resumeBefore < 5 && System.nanoTime() - controlsRecoveryStarted < TimeUnit.SECONDS.toNanos(3)) {
                    compose.waitForIdle()
                    Thread.sleep(50)
                }
                val controlsResumeMs = (System.nanoTime() - controlsRecoveryStarted) / 1_000_000
                assertTrue("Camera controls did not produce five fresh frames within three seconds", frames.get() - resumeBefore >= 5)
                val controlsBefore=frames.get()
                val controlsUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
                while (System.nanoTime() < controlsUntil) {
                    compose.waitForIdle()
                    Thread.sleep(50)
                }
                val controlsFrames=frames.get()-controlsBefore
                val controlsReport = JSONObject().put("scopeId",liveId)
                    .put("freshVideoFramesAfterMuteAndCameraReplacement",controlsFrames)
                    .put("fiveFreshFramesAfterControlsMs", controlsResumeMs)
                    .put("sampleMs",1500)
                if (controlsFrames < 5) controlsReport.put("failedRendererProbe", probeStoppedRenderer())
                File(folder,"sfu-before-outage-$role.json").writeText(controlsReport.toString(2))
                assertTrue("Camera controls already stopped remote video before the network outage",controlsFrames>=5)
                File(folder,"sfu-outage-safe-$role.json").writeText(JSONObject().put("scopeId",liveId).toString())
                assertTrue("Coordinator did not restore the test network",waitForPhase("sfu-network-restored.json",45))
                val recoveryStart = System.nanoTime()
                val recoveryDeadline = recoveryStart + TimeUnit.SECONDS.toNanos(25)
                fun incomingAudioPackets(): Long {
                    val latch = CountDownLatch(2)
                    val values = java.util.concurrent.ConcurrentLinkedQueue<livekit.org.webrtc.RTCStatsReport>()
                    room.getSubscriberRTCStats { values.add(it); latch.countDown() }
                    room.getPublisherRTCStats { values.add(it); latch.countDown() }
                    if (!latch.await(2,TimeUnit.SECONDS)) return 0
                    return values.flatMap { it.statsMap.values }.distinctBy { it.id }
                        .filter { it.type=="inbound-rtp" && it.members["kind"]=="audio" }
                        .sumOf { (it.members["packetsReceived"] as? Number)?.toLong() ?: 0L }
                }
                var previousAudio = incomingAudioPackets()
                var previousFrames = frames.get()
                var previousDrawnFrames = drawnFrames.get()
                val initialAudio=previousAudio
                val initialFrames=previousFrames
                val initialDrawnFrames=previousDrawnFrames
                var freshSamples = 0
                var freshDrawnSamples = 0
                var freshAudioSamples=0
                var firstFreshMs: Long? = null
                while (System.nanoTime()<recoveryDeadline && (freshSamples<8 || freshDrawnSamples<8 || freshAudioSamples<2)) {
                    Thread.sleep(250)
                    compose.waitForIdle()
                    val nextAudio=incomingAudioPackets()
                    val nextFrames=frames.get()
                    val nextDrawnFrames=drawnFrames.get()
                    // LiveKit enables Opus DTX for mono audio: silence does not
                    // produce a packet every 250 ms. Require fresh audio too,
                    // without classifying that expected silence as a disconnection.
                    if (nextAudio>previousAudio) freshAudioSamples++
                    if (nextFrames>previousFrames) freshSamples++ else freshSamples=0
                    if (nextDrawnFrames>previousDrawnFrames) freshDrawnSamples++ else freshDrawnSamples=0
                    if(nextAudio>initialAudio && freshSamples>0 && firstFreshMs==null)
                        firstFreshMs=(System.nanoTime()-recoveryStart)/1_000_000
                    previousAudio=nextAudio;previousFrames=nextFrames;previousDrawnFrames=nextDrawnFrames
                }
                val recoveryMs=(System.nanoTime()-recoveryStart)/1_000_000
                val recoveryStatsReady=CountDownLatch(2)
                val recoveryStats=java.util.concurrent.ConcurrentLinkedQueue<livekit.org.webrtc.RTCStatsReport>()
                room.getSubscriberRTCStats { recoveryStats.add(it);recoveryStatsReady.countDown() }
                room.getPublisherRTCStats { recoveryStats.add(it);recoveryStatsReady.countDown() }
                recoveryStatsReady.await(3,TimeUnit.SECONDS)
                val recoveryVideo=JSONArray(recoveryStats.flatMap {it.statsMap.values}.distinctBy {it.id}
                    .filter {it.members["kind"]=="video" && it.type in listOf("inbound-rtp","outbound-rtp")}
                    .map {stat -> JSONObject().put("direction",stat.type).apply {
                        listOf("framesDecoded","framesReceived","framesEncoded","framesSent","bytesReceived","bytesSent",
                            "keyFramesDecoded","keyFramesEncoded","freezeCount").forEach {key -> stat.members[key]?.let {put(key,it)}}
                    }})
                val recoveryReport = JSONObject().put("scopeId",liveId)
                    .put("firstFreshAudioAndVideoMs",firstFreshMs).put("eightFreshSamplesMs",recoveryMs)
                    .put("freshSamples",freshSamples).put("freshAudioSamples",freshAudioSamples)
                    .put("freshDrawnSamples",freshDrawnSamples)
                    .put("drawnFramesBefore",initialDrawnFrames).put("drawnFramesAfter",previousDrawnFrames)
                    .put("audioBefore",initialAudio).put("audioAfter",previousAudio)
                    .put("framesBefore",initialFrames).put("framesAfter",previousFrames)
                    .put("remoteVideos",bridge.remoteVideos.value.size).put("videoStats",recoveryVideo)
                    .put("bindingVersions",JSONArray(bridge.remoteVideos.value.map { it.bindingVersion }))
                    .put("trackEnabled",JSONArray(bridge.remoteVideos.value.map { runCatching { it.track.rtcTrack.enabled() }.getOrDefault(false) }))
                    .put("rendererAttached",renderer?.isAttachedToWindow==true)
                    .put("roomEvents",JSONArray(roomEvents.toList()))
                    .put("roomConnected",room.state==io.livekit.android.room.Room.State.CONNECTED)
                    .put("coordinatorRestoredMobileDataBeforeMeasurement",true)
                if (freshSamples < 8) recoveryReport.put("failedRendererProbe", probeStoppedRenderer())
                File(folder,"sfu-recovery-$role.json").writeText(recoveryReport.toString(2))
                assertTrue("Video did not recover",freshSamples>=8)
                assertTrue("Video did not draw fresh images",freshDrawnSamples>=8)
                assertTrue("No fresh audio after restoring data",freshAudioSamples>=2)
                assertTrue("Recovery exceeds ten seconds",recoveryMs<=10_000)
            }
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "sfu-duet-$role.json")
                .writeText(JSONObject().put("scopeId", liveId).put("role", role).put("audioReceived", audio)
                    .put("rendered", true).put("existingBroadcastPreserved",existingCapture != null).put("controlsAndReplacementCapture",existingCapture != null).put("receivedFps", fps).put("firstFrameMs", firstFrameMs).put("connectMs", connectMs).put("sessionRequestMs", sessionRequestMs).put("subscribeAndRenderMs", firstFrameMs - connectMs).put("startup", startup).toString(2))
        } finally {
            eventScope.cancel()
            File(folder,"sfu-done-$role.json").writeText(JSONObject().put("scopeId",liveId).toString())
            waitForPhase("sfu-stop-now.json",20)
            compose.runOnIdle { shown.value=false }
            compose.waitForIdle()
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
