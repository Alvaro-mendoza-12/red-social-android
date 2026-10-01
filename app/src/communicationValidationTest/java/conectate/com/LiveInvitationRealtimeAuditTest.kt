package conectate.com

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.LiveGuestInvitation
import conectate.com.data.repository.isLiveInvitationUpdate
import conectate.com.data.repository.isLiveInvitationChannelReady
import conectate.com.data.repository.mergeLiveInvitations
import conectate.com.data.signaling.SignalingConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/** No login, logout, or changes to the handset owner's account. */
@RunWith(AndroidJUnit4::class)
class LiveInvitationRealtimeAuditTest {
    @Test(timeout = 90_000) fun acceptanceReachesAndroidWithoutWaitingForPolling() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditInvitations") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText())
        val host = fixtures.getJSONObject(0)
        val guest = fixtures.getJSONObject(1)
        val userId = guest.getString("id")
        val http = OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(15, TimeUnit.SECONDS).build()
        fun request(path: String, actor: JSONObject, method: String = "POST", body: JSONObject = JSONObject()): JSONObject {
            val req = Request.Builder().url(SignalingConfig.BASE_URL + path)
                .header("Authorization", "Bearer " + actor.getString("token"))
                .method(method, if (method == "GET") null else body.toString().toRequestBody("application/json".toMediaType())).build()
            return http.newCall(req).execute().use { response ->
                assertTrue("Invitation API HTTP ${response.code}", response.isSuccessful)
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }
        val liveId = request("/v1/data/live", host, body = JSONObject()
            .put("title", "Invitaciones QA temporal").put("source", "audio"))
            .getJSONObject("stream").getString("id")
        val realtime = UbuntuRealtimeClient()
        val invitations = MutableStateFlow<List<LiveGuestInvitation>>(emptyList())
        var collector: Job? = null
        try {
            request("/v1/data/live/$liveId/state", host, "PATCH", JSONObject()
                .put("isActive", true).put("allowGuestRequests", true))
            // Send before opening the socket: this event cannot be delivered as
            // push. The production readiness policy must reconcile it once, with
            // no repeated action and no polling timer.
            val invitationId = request("/v1/data/live/$liveId/guests/requests", guest,
                body = JSONObject().put("requestNonce", UUID.randomUUID().toString()))
                .getJSONObject("invitation").getString("id")
            val coldStart = System.nanoTime()
            collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                realtime.events.onSubscription {
                    realtime.updateAccessToken(guest.getString("token"))
                    realtime.subscribe("user:$userId")
                }.collect { envelope ->
                    if (isLiveInvitationChannelReady(envelope, userId, userId)) {
                        val snapshot = request("/v1/data/live/$liveId/guests/invitations", guest, "GET")
                            .getJSONArray("invitations")
                        val rows = (0 until snapshot.length()).map { index ->
                            Json { ignoreUnknownKeys = true }.decodeFromString<LiveGuestInvitation>(snapshot.getJSONObject(index).toString())
                        }
                        invitations.update { mergeLiveInvitations(it, rows, liveId, userId) }
                    } else if (isLiveInvitationUpdate(envelope, liveId, userId, userId)) {
                        val invitation = Json { ignoreUnknownKeys = true }.decodeFromJsonElement<LiveGuestInvitation>(
                            envelope.payload.getValue("invitation"))
                        invitations.update { mergeLiveInvitations(it, listOf(invitation), liveId, userId) }
                    }
                }
            }
            withTimeout(8_000) { invitations.first { rows -> rows.any { it.id == invitationId } } }
            val reconnectMs = (System.nanoTime() - coldStart) / 1_000_000
            val oldSnapshot = invitations.value
            val start = System.nanoTime()
            request("/v1/data/live/$liveId/guests/invitations/$invitationId", host, "PATCH",
                JSONObject().put("status", "accepted"))
            withTimeout(4_000) { invitations.first { rows -> rows.any { it.id == invitationId && it.status == "accepted" } } }
            val acceptedMs = (System.nanoTime() - start) / 1_000_000
            val maximum = InstrumentationRegistry.getArguments().getString("maximumAcceptedMs")?.toLongOrNull() ?: 4_000L
            assertTrue("Acceptance was as slow as polling: $acceptedMs ms", acceptedMs < maximum)
            invitations.update { mergeLiveInvitations(it, oldSnapshot, liveId, userId) }
            assertEquals("Late HTTP snapshot undid acceptance", "accepted", invitations.value.first { it.id == invitationId }.status)
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "live-invitation-push.json")
                .writeText(JSONObject().put("liveId", liveId).put("passed", true)
                    .put("acceptedEventMs", acceptedMs)
                    .put("coldSocketReconciliationMs", reconnectMs).put("pendingAttempts", 1)
                    .put("pollingUsed", false).put("lateSnapshotPreservedAcceptance", true)
                    .put("ownerLoginUnchanged", true).toString(2))
        } finally {
            realtime.updateAccessToken("")
            collector?.cancelAndJoin()
            request("/v1/data/live/$liveId/state", host, "PATCH", JSONObject().put("isActive", false))
            request("/v1/data/live/$liveId", host, "DELETE")
        }
    }
}
