package conectate.com.data.localfallback

import android.util.Log
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

data class UbuntuRealtimeEnvelope(
    val channel: String,
    val event: String,
    val payload: JsonObject,
    val connectionGeneration: Long = -1L,
    val subscriptionGeneration: Long = -1L,
)

@Singleton
class UbuntuRealtimeClient internal constructor(
    private val captureOwner: () -> AuthSessionLease,
    private val currentAccessToken: () -> String?,
    private val commitOwner: (AuthSessionLease, () -> Unit) -> Boolean,
    private val openSocket: (Request, WebSocketListener) -> WebSocket,
) {
    @Inject constructor(auth: AuthService) : this(
        auth::captureAccountLease, auth::getAccessToken, auth::commitForAccountLease,
        OkHttpClient.Builder().dns(PublicBackendDns).build()::newWebSocket,
    )

    companion object {
        private const val TAG = "UbuntuRealtime"
        // The gateway accepts four concurrent authorization checks. Leave a slot
        // for the completion of an acknowledgement already travelling to us.
        private const val MAX_PENDING_SUBSCRIPTIONS = 3
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    // Desired rooms survive a token refresh. The remaining sets belong only to
    // the current connection and are accessed under the transport monitor.
    private val subscribedChannels = linkedSetOf<String>()
    private val awaitingSubscriptions = mutableSetOf<String>()
    private val activeSubscriptions = mutableSetOf<String>()
    private val deniedSubscriptions = mutableSetOf<String>()
    private val _events = MutableSharedFlow<UbuntuRealtimeEnvelope>(extraBufferCapacity = 256)
    val events: Flow<UbuntuRealtimeEnvelope> = _events.filter(::isCurrent)

    fun eventsOnSubscription(action: suspend FlowCollector<UbuntuRealtimeEnvelope>.() -> Unit): Flow<UbuntuRealtimeEnvelope> =
        _events.onSubscription(action).filter(::isCurrent)

    @Volatile private var socket: WebSocket? = null
    private var socketReady = false
    @Volatile private var accessToken = ""
    @Volatile private var socketGeneration = 0L
    @Volatile private var userSubscriptionGeneration = 0L
    @Volatile private var accountOwner: AuthSessionLease? = null

    // A token rotation or another consumer is the same visit. The authentication
    // lease rejects A -> B -> A even before an asynchronous observer runs.
    fun isCurrent(envelope: UbuntuRealtimeEnvelope): Boolean {
        val owner = accountOwner ?: return false
        return owner.isCurrent() && accountOwner === owner &&
            envelope.subscriptionGeneration == userSubscriptionGeneration
    }

    // Lock order is auth gate -> transport. Never call auth while holding the
    // transport monitor: message storage and publication use that same gate.
    private fun forOwner(owner: AuthSessionLease, action: () -> Unit) {
        commitOwner(owner) { synchronized(this) { action() } }
    }

    private fun closeSocketLocked(reason: String) {
        socketGeneration++
        val previous = socket
        socket = null
        socketReady = false
        clearConnectionSubscriptionsLocked()
        previous?.close(1000, reason)
    }

    private fun clearConnectionSubscriptionsLocked() {
        awaitingSubscriptions.clear()
        activeSubscriptions.clear()
        deniedSubscriptions.clear()
    }

    private fun bindOwnerLocked(owner: AuthSessionLease) {
        if (accountOwner?.sameVisit(owner) == true) return
        userSubscriptionGeneration++
        subscribedChannels.clear()
        accessToken = ""
        closeSocketLocked("account-changed")
        accountOwner = owner
    }

    fun updateAccessToken(token: String) {
        val owner = captureOwner()
        val clean = token.trim()
        if (owner.accountId == null) { clearUserSubscriptions(); return }
        commitOwner(owner) {
            if (clean == currentAccessToken()?.trim().orEmpty()) synchronized(this) {
                bindOwnerLocked(owner)
                if (clean != accessToken) {
                    accessToken = clean
                    closeSocketLocked("token-refresh")
                    ensureConnectedLocked(owner)
                }
            }
        }
    }

    fun replaceUserSubscription(userId: String) {
        val owner = captureOwner()
        val clean = userId.trim().lowercase(java.util.Locale.ROOT)
        if (clean.isBlank() || clean != owner.accountId) return
        forOwner(owner) {
            bindOwnerLocked(owner)
            // Repeated consumers neither reconnect nor revoke accepted frames.
            subscribeLocked("user:$clean", owner)
        }
    }

    /** Old cleanup cannot erase a newer account or a same-visit token refresh. */
    fun clearUserSubscriptions() {
        val previous = accountOwner ?: return
        if (previous.isCurrent()) return
        synchronized(this) {
            if (accountOwner !== previous) return
            userSubscriptionGeneration++
            subscribedChannels.clear()
            accessToken = ""
            accountOwner = null
            closeSocketLocked("session-ended")
        }
    }

    fun subscribe(channel: String) {
        val owner = captureOwner()
        val clean = channel.trim()
        if (clean.isBlank() || clean.length > 256 || (clean.startsWith("user:") && clean != "user:${owner.accountId}")) return
        forOwner(owner) { bindOwnerLocked(owner); subscribeLocked(clean, owner) }
    }

    private fun subscribeLocked(channel: String, owner: AuthSessionLease) {
        subscribedChannels.add(channel)
        ensureConnectedLocked(owner)
        drainSubscriptionsLocked()
    }

    private fun command(type: String, channel: String) = buildJsonObject {
        put("type", type)
        put("channel", channel)
    }.toString()

    private fun drainSubscriptionsLocked() {
        if (!socketReady) return
        val transport = socket ?: return
        for (channel in subscribedChannels) {
            if (awaitingSubscriptions.size >= MAX_PENDING_SUBSCRIPTIONS) break
            if (channel in awaitingSubscriptions || channel in activeSubscriptions || channel in deniedSubscriptions) continue
            awaitingSubscriptions.add(channel)
            if (!transport.send(command("subscribe", channel))) {
                awaitingSubscriptions.remove(channel)
                break // OkHttp's closing/failure callback owns reconnection.
            }
        }
    }

    fun unsubscribe(channel: String) {
        val owner = captureOwner()
        val clean = channel.trim()
        if (clean.isBlank()) return
        forOwner(owner) {
            if (accountOwner?.sameVisit(owner) == true && subscribedChannels.remove(clean)) {
                activeSubscriptions.remove(clean)
                deniedSubscriptions.remove(clean)
                // An outstanding authorization still occupies a gateway slot.
                // Release it on its reply and undo any late successful join.
                if (socketReady) socket?.send(command("unsubscribe", clean))
            }
        }
    }

    private fun wsUrlLocked(): String? {
        val base = RuntimeBackendEndpoint.baseUrl.trim().removeSuffix("/")
        if (!base.startsWith("http") || accessToken.isBlank()) return null
        return base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/realtime/"
    }

    private fun ensureConnectedLocked(owner: AuthSessionLease) {
        if (socket != null) return
        val url = wsUrlLocked() ?: return
        val generation = ++socketGeneration
        val connectionToken = accessToken
        val subscription = userSubscriptionGeneration
        val request = Request.Builder().url(url).header("Authorization", "Bearer $connectionToken").build()
        socket = openSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                forOwner(owner) {
                    if (generation == socketGeneration && connectionToken == accessToken) handleMessage(text, generation, subscription)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure", t)
                if (!detachCurrentSocket(webSocket, generation, connectionToken)) return
                scope.launch { RuntimeBackendEndpoint.refreshAfterEndpointFailure(); delay(900); reconnectIfCurrent(owner) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!detachCurrentSocket(webSocket, generation, connectionToken)) return
                scope.launch { delay(900); reconnectIfCurrent(owner) }
            }
        })
    }

    private fun reconnectIfCurrent(owner: AuthSessionLease) = forOwner(owner) {
        if (accountOwner?.sameVisit(owner) == true) ensureConnectedLocked(owner)
    }

    @Synchronized private fun detachCurrentSocket(webSocket: WebSocket, generation: Long, token: String): Boolean {
        if (socket !== webSocket || socketGeneration != generation || accessToken != token) return false
        socketGeneration++
        socket = null
        socketReady = false
        clearConnectionSubscriptionsLocked()
        return true
    }

    internal suspend fun publishAcceptedEnvelope(envelope: UbuntuRealtimeEnvelope) {
        if (isCurrent(envelope)) _events.emit(envelope)
    }

    private fun handleMessage(text: String, generation: Long, subscription: Long) {
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val type = root["type"]?.jsonPrimitive?.contentOrNull
            if (type == "connected") {
                socketReady = true
                drainSubscriptionsLocked()
                return
            }
            val channel = root["channel"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (type == "error" && channel.isNotBlank()) {
                awaitingSubscriptions.remove(channel)
                activeSubscriptions.remove(channel)
                if (channel in subscribedChannels) deniedSubscriptions.add(channel)
                drainSubscriptionsLocked()
                return
            }
            if (type != "event" && type != "subscribed") return
            if (channel.isBlank()) return
            if (type == "subscribed") {
                if (!awaitingSubscriptions.remove(channel)) return
                if (channel !in subscribedChannels) {
                    socket?.send(command("unsubscribe", channel))
                    drainSubscriptionsLocked()
                    return
                }
                activeSubscriptions.add(channel)
                drainSubscriptionsLocked()
            }
            val payload = root["payload"]?.jsonObject ?: JsonObject(emptyMap())
            val event = if (type == "subscribed") "subscription_ready" else payload["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val admitted = UbuntuRealtimeEnvelope(channel, event, payload, generation, subscription)
            scope.launch { publishAcceptedEnvelope(admitted) }
        }.onFailure { Log.w(TAG, "invalid realtime payload", it) }
    }
}
