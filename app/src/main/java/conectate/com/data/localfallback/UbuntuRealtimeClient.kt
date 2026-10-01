package conectate.com.data.localfallback

import android.util.Log
import conectate.com.core.network.PublicBackendDns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
)

@Singleton
class UbuntuRealtimeClient @Inject constructor() {
    companion object {
        private const val TAG = "UbuntuRealtime"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().dns(PublicBackendDns).build()
    private val subscribedChannels = ConcurrentHashMap.newKeySet<String>()
    private val _events = MutableSharedFlow<UbuntuRealtimeEnvelope>(extraBufferCapacity = 256)
    val events: SharedFlow<UbuntuRealtimeEnvelope> = _events.asSharedFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var accessToken: String = ""
    @Volatile private var socketGeneration = 0L

    @Synchronized
    fun updateAccessToken(token: String) {
        val clean = token.trim()
        if (clean == accessToken) return
        accessToken = clean
        if (clean.isBlank()) {
            socketGeneration++
            val previous = socket
            socket = null
            previous?.close(1000, "session-ended")
        } else {
            reconnect()
        }
    }

    fun replaceUserSubscription(userId: String) {
        val channel = "user:${userId.trim().lowercase()}"
        if (channel == "user:") return
        subscribedChannels
            .filter { it.startsWith("user:") && it != channel }
            .forEach { staleChannel ->
                sendJson("""{"type":"unsubscribe","channel":"$staleChannel"}""")
                subscribedChannels.remove(staleChannel)
            }
        subscribe(channel)
    }

    fun clearUserSubscriptions() {
        subscribedChannels
            .filter { it.startsWith("user:") }
            .forEach { channel ->
                sendJson("""{"type":"unsubscribe","channel":"$channel"}""")
                subscribedChannels.remove(channel)
            }
    }

    fun subscribe(channel: String) {
        val clean = channel.trim()
        if (clean.isEmpty()) return
        subscribedChannels.add(clean)
        ensureConnected()
        sendJson(
            """{"type":"subscribe","channel":"$clean"}""",
        )
    }

    fun unsubscribe(channel: String) {
        val clean = channel.trim()
        if (clean.isEmpty()) return
        subscribedChannels.remove(clean)
        sendJson(
            """{"type":"unsubscribe","channel":"$clean"}""",
        )
    }

    private fun wsUrl(): String? {
        val base = RuntimeBackendEndpoint.baseUrl.trim().removeSuffix("/")
        if (!base.startsWith("http")) return null
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        if (accessToken.isBlank()) return null
        return "$wsBase/realtime/"
    }

    @Synchronized
    private fun ensureConnected() {
        if (socket != null) return
        val url = wsUrl() ?: return
        val generation = ++socketGeneration
        val connectionToken = accessToken
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $connectionToken")
            .build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (generation != socketGeneration || connectionToken != accessToken) return
                    subscribedChannels.forEach { channel ->
                        webSocket.send("""{"type":"subscribe","channel":"$channel"}""")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (generation != socketGeneration || connectionToken != accessToken) return
                    handleMessage(text, generation, connectionToken)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "socket failure", t)
                    if (!detachCurrentSocket(webSocket, generation, connectionToken)) return
                    scope.launch {
                        RuntimeBackendEndpoint.refreshAfterEndpointFailure()
                        delay(900)
                        ensureConnected()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!detachCurrentSocket(webSocket, generation, connectionToken)) return
                    scope.launch {
                        delay(900)
                        ensureConnected()
                    }
                }
            },
        )
    }

    @Synchronized
    private fun detachCurrentSocket(webSocket: WebSocket, generation: Long, connectionToken: String): Boolean {
        if (socket !== webSocket || socketGeneration != generation || accessToken != connectionToken) return false
        socketGeneration++
        socket = null
        return true
    }

    @Synchronized
    private fun reconnect() {
        socketGeneration++
        val previous = socket
        socket = null
        previous?.close(1000, "token-refresh")
        ensureConnected()
    }

    private fun sendJson(raw: String) {
        socket?.send(raw)
    }

    private fun handleMessage(text: String, generation: Long, connectionToken: String) {
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val type = root["type"]?.jsonPrimitive?.contentOrNull
            if (type != "event" && type != "subscribed") return
            val channel = root["channel"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val payload = root["payload"]?.jsonObject ?: JsonObject(emptyMap())
            val event = if (type == "subscribed") "subscription_ready" else payload["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (channel.isBlank()) return
            scope.launch {
                if (generation != socketGeneration || connectionToken != accessToken) return@launch
                _events.emit(UbuntuRealtimeEnvelope(channel = channel, event = event, payload = payload))
            }
        }.onFailure {
            Log.w(TAG, "invalid realtime payload", it)
        }
    }
}
