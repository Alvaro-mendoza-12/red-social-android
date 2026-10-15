package conectate.com

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.repository.LiveRepository
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import conectate.com.presentation.components.WebRtcVideoView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Provider

/** Both endpoints execute the product repository; the host offers automatically on viewer entry. */
@RunWith(AndroidJUnit4::class)
class LiveRepositoryPhysicalAuditTest {
    @get:Rule val compose=createComposeRule()
    @Suppress("UNCHECKED_CAST")
    private fun numericVideoStats(repository: LiveRepository): JSONArray {
        val manager=LiveRepository::class.java.getDeclaredField("webRTCManager").apply {isAccessible=true}
            .get(repository) as? conectate.com.data.webrtc.WebRTCManager ?: return JSONArray()
        val peers=manager.javaClass.getDeclaredField("peerConnections").apply {isAccessible=true}
            .get(manager) as ConcurrentHashMap<String,org.webrtc.PeerConnection>
        val executor=manager.javaClass.getDeclaredField("executor").apply {isAccessible=true}
            .get(manager) as conectate.com.data.webrtc.MediaTaskExecutor
        val ready=CountDownLatch(1)
        var report:org.webrtc.RTCStatsReport?=null
        executor.execute(onUnavailable={ready.countDown()}) {
            val peer=peers.values.firstOrNull()
            if(peer==null)ready.countDown() else peer.getStats {report=it;ready.countDown()}
        }
        ready.await(3,TimeUnit.SECONDS)
        val stats=report?.statsMap?.values.orEmpty()
        return JSONArray(stats.filter {it.members["kind"]=="video" && it.type in listOf("inbound-rtp","outbound-rtp")}.map {stat ->
            JSONObject().put("direction",stat.type).apply {
                listOf("framesDecoded","framesEncoded","framesReceived","framesSent","framesDropped","decoderImplementation",
                    "encoderImplementation","powerEfficientDecoder","powerEfficientEncoder","bytesReceived","bytesSent",
                    "qualityLimitationReason").forEach {key -> stat.members[key]?.let {put(key,it)}}
                stats.firstOrNull {it.id==stat.members["codecId"]}?.members?.get("mimeType")?.let {put("codec",it)}
            }
        })
    }
    @Test(timeout=180_000) fun actualHostAndViewerConnectOnSeparateDevices() {
        val args=InstrumentationRegistry.getArguments()
        val role=args.getString("actualLiveRole")
        val verifyGuest=args.getString("auditActualGuest")=="true"
        assumeTrue(role in listOf("host","viewer"))
        val scope=UUID.fromString(args.getString("ownedLiveFixtureId")).toString()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("conectate.com.qa.cierre11",context.packageName)
        val fixtures=JSONArray(File(context.cacheDir,"media-qa-login-private.json").readText())
        assertEquals(2,fixtures.length())
        val actor=fixtures.getJSONObject(if(role=="host") 0 else 1)
        val ownedIds=(0..1).map { fixtures.getJSONObject(it).getString("id") }
        val entry=EntryPointAccessors.fromApplication(context,MessageNotificationReceiverEntryPoint::class.java)
        val auth=entry.authService()
        compose.waitUntil(15_000) { !auth.isInitializing.value }
        val previous=auth.getCurrentUserId()
        assertTrue("Only empty or disposable sessions may be switched",previous==null || previous in ownedIds)
        val previousActor=(0..1).map(fixtures::getJSONObject).firstOrNull { it.getString("id")==previous }
        val provider=entry.javaClass.getDeclaredField("liveRepositoryProvider").apply { isAccessible=true }.get(entry) as Provider<*>
        val repository=provider.get() as LiveRepository
        assertNull("Do not interrupt another LIVE",repository.activeSession.value)
        val folder=context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        val firstFrame=AtomicLong(); val frames=AtomicInteger()
        val receivedGuestFrame=AtomicLong()
        val localGuestFrames=AtomicInteger()
        var localGuestProbeTrack:org.webrtc.VideoTrack?=null
        val localGuestProbe=org.webrtc.VideoSink {localGuestFrames.incrementAndGet()}
        var guestAcceptedAt=0L
        var guestSetupMs:Long?=null
        var guestProbeTrack:org.webrtc.VideoTrack?=null
        val guestProbe=org.webrtc.VideoSink {receivedGuestFrame.compareAndSet(0,System.nanoTime())}
        var countedTrack:org.webrtc.VideoTrack?=null
        val sink=org.webrtc.VideoSink { frames.incrementAndGet() }
        val shown=mutableStateOf(true)
        fun waitPhase(name:String,seconds:Long):Boolean {
            val file=File(context.cacheDir,name)
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds)
            while(System.nanoTime()<end) {
                if(runCatching {JSONObject(file.readText()).optString("scopeId")==scope}.getOrDefault(false))return true
                Thread.sleep(100)
            };return false
        }
        try {
            if(previous!=actor.getString("id"))runBlocking {auth.signIn(actor.getString("email"),actor.getString("password")).getOrThrow()}
            assertEquals(actor.getString("id"),auth.getCurrentUserId())
            compose.setContent {
                val local=repository.localVideoTrack.collectAsState().value
                val remote=repository.remoteVideoTracks.collectAsState().value.values.firstOrNull()
                val egl=repository.eglBaseContext.collectAsState().value
                val showRemote=role!="host" || (verifyGuest && remote!=null)
                MaterialTheme {if(shown.value)WebRtcVideoView(if(showRemote)remote else local,egl,Modifier.fillMaxSize(),
                    onFirstFrameRendered={
                        firstFrame.compareAndSet(0,System.nanoTime())
                        if(role=="host" && showRemote)receivedGuestFrame.compareAndSet(0,System.nanoTime())
                    })}
            }
            val start=System.nanoTime()
            runBlocking {if(role=="host")repository.hostLive(scope).getOrThrow() else repository.joinLiveAsViewer(scope).getOrThrow()}
            val requestMs=(System.nanoTime()-start)/1_000_000
            if(role=="host")assertTrue("Camera transition rejected",repository.startCamera())
            compose.waitUntil(30_000) {firstFrame.get()>0}
            val firstFrameMs=(firstFrame.get()-start)/1_000_000
            if(role=="host") {
                File(folder,"actual-live-host-ready.json").writeText(JSONObject().put("scopeId",scope).toString())
                if(verifyGuest) {
                    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
                    var responded=false
                    while(receivedGuestFrame.get()==0L && System.nanoTime()<deadline) {
                        if(!responded)repository.guestRequests.value.firstOrNull {
                            it.userId==fixtures.getJSONObject(1).getString("id")
                        }?.let { request ->
                            guestAcceptedAt=System.nanoTime()
                            repository.respondToGuestRequest(request.socketId,true)
                            responded=true
                        }
                        if(guestProbeTrack==null) repository.remoteVideoTracks.value.values.firstOrNull()?.let {
                            guestProbeTrack=it;it.addSink(guestProbe)
                        }
                        Thread.sleep(100)
                    }
                    File(folder,"actual-live-guest-host-diagnostic.json").writeText(JSONObject().put("scopeId",scope)
                        .put("requestReceived",responded).put("remoteTrackAvailable",guestProbeTrack!=null)
                        .put("guestPictureReceived",receivedGuestFrame.get()>0)
                        .put("activeGuests",repository.activeGuests.value.size).toString(2))
                    assertTrue("Actual guest request was not received",responded)
                    assertTrue("Accepted guest camera never rendered on the host",receivedGuestFrame.get()>0)
                    guestSetupMs=(receivedGuestFrame.get()-guestAcceptedAt)/1_000_000
                    countedTrack=repository.remoteVideoTracks.value.values.firstOrNull()
                } else {
                    assertTrue("Viewer did not render",waitPhase("actual-live-viewer-rendered.json",60))
                    countedTrack=repository.localVideoTrack.value
                }
            } else {
                if(verifyGuest) {
                    val guestStart=System.nanoTime()
                    repository.requestGuestAccess()
                    compose.waitUntil(30_000) {
                        repository.guestAccessState.value.status==conectate.com.data.model.LiveGuestRequestStatus.ACCEPTED
                    }
                    assertTrue("Accepted guest could not start camera/microphone",repository.startGuestBroadcast(true,true))
                    compose.waitUntil(15_000) {repository.localVideoTrack.value!=null}
                    localGuestProbeTrack=repository.localVideoTrack.value
                    localGuestProbeTrack!!.addSink(localGuestProbe)
                    guestSetupMs=(System.nanoTime()-guestStart)/1_000_000
                }
                countedTrack=repository.remoteVideoTracks.value.values.firstOrNull()
                File(folder,"actual-live-viewer-ready.json").writeText(JSONObject().put("scopeId",scope).toString())
            }
            assertNotNull(countedTrack);countedTrack!!.addSink(sink)
            val before=frames.get();val sample=System.nanoTime();Thread.sleep(15_000)
            val fps=(frames.get()-before)*1_000_000_000.0/(System.nanoTime()-sample)
            assertEquals(actor.getString("id"),auth.getCurrentUserId())
            File(folder,"actual-live-$role-result.json").writeText(JSONObject().put("scopeId",scope)
                .put("role",role).put("joinRequestMs",requestMs).put("firstFrameMs",firstFrameMs)
                .put("receivedOrCapturedFps",fps).put("sampleMs",15_000)
                .put("startupPhases",JSONObject(repository.viewerStartupPhases.value))
                .put("actualGuestFlowExercised",verifyGuest)
                .put("guestCameraReceivedByHost",role=="host" && receivedGuestFrame.get()>0)
                .put("guestCapturedFrames",localGuestFrames.get()).put("guestRequestOrAcceptedToPictureMs",guestSetupMs)
                .put("sampleDirection",if(role=="host" && verifyGuest) "guestCameraReceived" else if(role=="viewer") "hostCameraReceived" else "localHostCapture")
                .put("numericVideoStats",numericVideoStats(repository))
                .put("actualHostAndViewerRepositories",true).put("includesCoordinatorPollingOverhead",false)
                .put("onlyOwnQaSessionUsed",true).toString(2))
            assertTrue("Video frames stalled: $fps",fps>=8)
            if(role=="viewer")assertTrue("LIVE first picture exceeds five seconds: $firstFrameMs",firstFrameMs<5_000)
        } finally {
            guestProbeTrack?.let {runCatching {it.removeSink(guestProbe)}}
            localGuestProbeTrack?.let {runCatching {it.removeSink(localGuestProbe)}}
            countedTrack?.let {runCatching {it.removeSink(sink)}}
            File(folder,"actual-live-$role-done.json").writeText(JSONObject().put("scopeId",scope).toString())
            waitPhase("actual-live-stop.json",25)
            compose.runOnIdle {shown.value=false}
            if(repository.activeSession.value?.stream?.id==scope)runBlocking {repository.endLive().getOrThrow()}
            if(auth.getCurrentUserId()!=previous)runBlocking {
                if(previousActor==null)auth.signOut()
                else auth.signIn(previousActor.getString("email"),previousActor.getString("password")).getOrThrow()
            }
            assertEquals(previous,auth.getCurrentUserId())
        }
    }
}
