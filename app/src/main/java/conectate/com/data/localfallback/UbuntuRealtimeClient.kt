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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
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

    companion object { private const val TAG = "UbuntuRealtime" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val subscribedChannels = ConcurrentHashMap.newKeySet<String>()
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
        previous?.close(1000, reason)
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
        if (clean.isBlank() || (clean.startsWith("user:") && clean != "user:${owner.accountId}")) return
        forOwner(owner) { bindOwnerLocked(owner); subscribeLocked(clean, owner) }
    }

    private fun subscribeLocked(channel: String, owner: AuthSessionLease) {
        val added = subscribedChannels.add(channel)
        ensureConnectedLocked(owner)
        if (added && socketReady) socket?.send("""{"type":"subscribe","channel":"$channel"}""")
    }

    fun unsubscribe(channel: String) {
        val owner = captureOwner()
        val clean = channel.trim()
        if (clean.isBlank()) return
        forOwner(owner) {
            if (accountOwner?.sameVisit(owner) == true && subscribedChannels.remove(clean)) {
                socket?.send("""{"type":"unsubscribe","channel":"$clean"}""")
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
            override fun onOpen(webSocket: WebSocket, response: Response) {
                forOwner(owner) {
                    if (generation == socketGeneration && connectionToken == accessToken) {
                        socketReady = true
                        subscribedChannels.forEach { webSocket.send("""{"type":"subscribe","channel":"$it"}""") }
                    }
                }
            }

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
        return true
    }

    internal suspend fun publishAcceptedEnvelope(envelope: UbuntuRealtimeEnvelope) {
        if (isCurrent(envelope)) _events.emit(envelope)
    }

    private fun handleMessage(text: String, generation: Long, subscription: Long) {
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val type = root["type"]?.jsonPrimitive?.contentOrNull
            if (type != "event" && type != "subscribed") return
            val channel = root["channel"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val payload = root["payload"]?.jsonObject ?: JsonObject(emptyMap())
            val event = if (type == "subscribed") "subscription_ready" else payload["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (channel.isBlank()) return
            val admitted = UbuntuRealtimeEnvelope(channel, event, payload, generation, subscription)
            scope.launch { publishAcceptedEnvelope(admitted) }
        }.onFailure { Log.w(TAG, "invalid realtime payload", it) }
    }
}
