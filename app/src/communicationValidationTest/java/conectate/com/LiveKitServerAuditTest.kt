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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in SDK transport check. Test credentials stay inside the handset cache. */
@RunWith(AndroidJUnit4::class)
class LiveKitServerAuditTest {
    @get:Rule val compose=createComposeRule()
    @Test(timeout=180_000) fun collaborationSfuTransmitsAndRendersActualCameraFrames() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditLiveKit")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val fixtures=JSONArray(File(context.cacheDir,"media-qa-accounts.json").readText())
        val client=OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(20,TimeUnit.SECONDS).build()
        fun request(path: String,index: Int,method: String="POST",body: JSONObject=JSONObject()): JSONObject {
            val req=Request.Builder().url(SignalingConfig.BASE_URL+path)
                .header("Authorization","Bearer "+fixtures.getJSONObject(index).getString("token"))
                .method(method,body.toString().toRequestBody("application/json".toMediaType())).build()
            return client.newCall(req).execute().use { response ->
                assertTrue("SFU API HTTP ${response.code}",response.isSuccessful)
                val text=response.body?.string().orEmpty()
                if(text.isBlank()) JSONObject() else JSONObject(text)
            }
        }
        fun session(id: String,index: Int,role: String): LiveSfuSession {
            val data=request("/v1/data/live/$id/sfu-token",index,body=JSONObject().put("role",role))
            return LiveSfuSession(wsUrl=data.getString("wsUrl"),roomName=data.getString("roomName"),token=data.getString("token"),role=role)
        }
        val host=LiveKitRoomBridge(context);val viewer=LiveKitRoomBridge(context)
        val frames=AtomicInteger();val rendered=CountDownLatch(1)
        var renderer: io.livekit.android.renderer.TextureViewRenderer?=null
        var video: LiveKitRoomBridge.RemoteVideo?=null
        val liveId=request("/v1/data/live",0,body=JSONObject().put("title","Prueba SFU temporal").put("source","camera"))
            .getJSONObject("stream").getString("id")
        try {
            request("/v1/data/live/$liveId/state",0,"PATCH",JSONObject().put("isActive",true).put("allowGuestRequests",true))
            val started=System.nanoTime()
            runBlocking { withTimeout(35_000) { host.connect(session(liveId,0,"host"),publishAudio=false,publishCamera=true).getOrThrow() } }
            runBlocking { withTimeout(35_000) { viewer.connect(session(liveId,1,"viewer"),publishAudio=false,publishCamera=false).getOrThrow() } }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(viewer.remoteVideos.value.isEmpty() && System.nanoTime()<deadline) Thread.sleep(100)
            assertTrue("SFU did not subscribe a camera track",viewer.remoteVideos.value.isNotEmpty())
            video=viewer.remoteVideos.value.first()
            instrumentation.runOnMainSync {
                renderer=object: io.livekit.android.renderer.TextureViewRenderer(context) {
                    override fun onFrame(frame: livekit.org.webrtc.VideoFrame) { frames.incrementAndGet();super.onFrame(frame) }
                }
                assertTrue(viewer.attachRenderer(video!!,renderer!!))
                renderer!!.addFrameListener({ bitmap -> bitmap?.recycle();rendered.countDown() },0.1f)
            }
            compose.setContent { MaterialTheme { AndroidView(factory={renderer!!},modifier=Modifier.fillMaxSize()) } }
            assertTrue("SFU decoded no actual image",rendered.await(30,TimeUnit.SECONDS))
            val firstFrameMs=(System.nanoTime()-started)/1_000_000
            val before=frames.get();val sample=System.nanoTime();Thread.sleep(5_000)
            val fps=(frames.get()-before)*1_000_000_000.0/(System.nanoTime()-sample)
            assertTrue("SFU video is too slow: $fps",fps>=8)
            val firstTrackId=video!!.trackId
            instrumentation.runOnMainSync { viewer.detachRenderer(video!!,renderer!!) }
            runBlocking { viewer.disconnectAwait();host.disconnectAwait() }
            assertTrue(viewer.remoteVideos.value.isEmpty())
            assertEquals(LiveKitRoomBridge.State.DISCONNECTED,viewer.state.value)
            runBlocking {
                withTimeout(35_000) {
                    host.connect(session(liveId,0,"host"),false,true).getOrThrow()
                    viewer.connect(session(liveId,1,"viewer"),false,false).getOrThrow()
                }
            }
            val restartedDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(viewer.remoteVideos.value.isEmpty() && System.nanoTime()<restartedDeadline) Thread.sleep(100)
            assertTrue("SFU room did not reconnect",viewer.remoteVideos.value.isNotEmpty())
            assertNotEquals("Old track survived the room replacement",firstTrackId,viewer.remoteVideos.value.first().trackId)
            File(context.getExternalFilesDir("communication-qa")!!,"livekit-camera.json").writeText(JSONObject()
                .put("firstFrameMs",firstFrameMs).put("receivedFps",fps).put("rendered",true).put("roomReconnect",true).toString(2))
        } finally {
            instrumentation.runOnMainSync { if(video!=null && renderer!=null) viewer.detachRenderer(video!!,renderer!!) }
            runBlocking { viewer.disconnectAwait();host.disconnectAwait() }
            runCatching { request("/v1/data/live/$liveId",0,"DELETE") }
        }
    }
}
