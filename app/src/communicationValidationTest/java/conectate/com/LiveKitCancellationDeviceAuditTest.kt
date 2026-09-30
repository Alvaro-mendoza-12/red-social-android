package conectate.com

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.livekit.LiveKitRoomBridge
import conectate.com.data.model.LiveSfuSession
import conectate.com.data.signaling.SignalingConfig
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LiveKitCancellationDeviceAuditTest {
    @Test(timeout = 120_000) fun cancellingSignalingAllowsACleanRetry() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditSfuCancellation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText()).getJSONObject(0)
        val client = OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(20, TimeUnit.SECONDS).build()
        fun api(path: String, method: String = "POST", body: JSONObject = JSONObject()): JSONObject {
            val request = Request.Builder().url(SignalingConfig.BASE_URL + path)
                .header("Authorization", "Bearer " + account.getString("token"))
                .method(method, body.toString().toRequestBody("application/json".toMediaType())).build()
            return client.newCall(request).execute().use { response ->
                assertTrue("Cancellation fixture HTTP ${response.code}", response.isSuccessful)
                JSONObject(response.body!!.string())
            }
        }
        val liveId = api("/v1/data/live", body = JSONObject().put("title", "Prueba aislada de cancelación LIVE").put("source", "audio"))
            .getJSONObject("stream").getString("id")
        val bridge = LiveKitRoomBridge(context)
        try {
            api("/v1/data/live/$liveId/state", "PATCH", JSONObject().put("isActive", true))
            val data = api("/v1/data/live/$liveId/sfu-token", body = JSONObject().put("role", "host"))
            val session = LiveSfuSession(wsUrl = data.getString("wsUrl"), roomName = data.getString("roomName"),
                token = data.getString("token"), role = "host")
            repeat(3) {
                val pending = launch(Dispatchers.IO) { bridge.connect(session, false, false).getOrThrow() }
                withTimeout(10_000) { while (bridge.state.value != LiveKitRoomBridge.State.CONNECTING) delay(1) }
                pending.cancelAndJoin()
                assertTrue("Pending signaling must remain cancelled", pending.isCancelled)
                assertEquals(LiveKitRoomBridge.State.DISCONNECTED, bridge.state.value)
                assertTrue(bridge.remoteVideos.value.isEmpty())
                val roomField = bridge.javaClass.getDeclaredField("room").apply { isAccessible = true }
                assertNull("Cancelled room was not released", roomField.get(bridge))
                withTimeout(20_000) { bridge.connect(session, false, false).getOrThrow() }
                assertEquals(LiveKitRoomBridge.State.CONNECTED, bridge.state.value)
                bridge.disconnectAwait()
            }
            File(context.getExternalFilesDir("communication-qa")!!, "sfu-cancellation.json")
                .writeText(JSONObject().put("cancelAndRetryCycles", 3).put("passed", true).toString())
        } finally {
            withContext(NonCancellable) { bridge.disconnectAwait() }
            api("/v1/data/live/$liveId/state", "PATCH", JSONObject().put("isActive", false))
            api("/v1/data/live/$liveId", "DELETE")
        }
    }
}
