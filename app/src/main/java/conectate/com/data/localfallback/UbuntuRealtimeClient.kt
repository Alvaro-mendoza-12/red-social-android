package conectate.com.data.localfallback

import android.util.Log
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
    private val client = OkHttpClient.Builder().build()
    private val subscribedChannels = ConcurrentHashMap.newKeySet<String>()
    private val _events = MutableSharedFlow<UbuntuRealtimeEnvelope>(extraBufferCapacity = 256)
    val events: SharedFlow<UbuntuRealtimeEnvelope> = _events.asSharedFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var accessToken: String = ""

    fun updateAccessToken(token: String) {
        val clean = token.trim()
        if (clean == accessToken) return
        accessToken = clean
        if (clean.isBlank()) {
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
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    subscribedChannels.forEach { channel ->
                        webSocket.send("""{"type":"subscribe","channel":"$channel"}""")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "socket failure", t)
                    if (socket !== webSocket) return
                    socket = null
                    scope.launch {
                        RuntimeBackendEndpoint.refreshAfterEndpointFailure()
                        delay(900)
                        ensureConnected()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket !== webSocket) return
                    socket = null
                    scope.launch {
                        delay(900)
                        ensureConnected()
                    }
                }
            },
        )
    }

    @Synchronized
    private fun reconnect() {
        val previous = socket
        socket = null
        previous?.close(1000, "token-refresh")
        ensureConnected()
    }

    private fun sendJson(raw: String) {
        socket?.send(raw)
    }

    private fun handleMessage(text: String) {
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            if (root["type"]?.jsonPrimitive?.contentOrNull != "event") return
            val channel = root["channel"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val payload = root["payload"]?.jsonObject ?: JsonObject(emptyMap())
            val event = payload["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (channel.isBlank()) return
            scope.launch {
                _events.emit(UbuntuRealtimeEnvelope(channel = channel, event = event, payload = payload))
            }
        }.onFailure {
            Log.w(TAG, "invalid realtime payload", it)
        }
    }
}
