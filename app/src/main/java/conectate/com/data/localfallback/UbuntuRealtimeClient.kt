package conectate.com.data.localfallback

import android.util.Log
import conectate.com.BuildConfig
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
        reconnect()
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
        val base = BuildConfig.FALLBACK_API_URL.trim().removeSuffix("/")
        if (!base.startsWith("http")) return null
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val token = accessToken
        if (token.isBlank()) return null
        return "$wsBase/realtime/?token=${java.net.URLEncoder.encode(token, Charsets.UTF_8.name())}"
    }

    private fun ensureConnected() {
        if (socket != null) return
        val url = wsUrl() ?: return
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    subscribedChannels.forEach { channel ->
                        sendJson("""{"type":"subscribe","channel":"$channel"}""")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "socket failure", t)
                    socket = null
                    scope.launch {
                        delay(2500)
                        ensureConnected()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                }
            },
        )
    }

    private fun reconnect() {
        socket?.close(1000, "token-refresh")
        socket = null
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
