package conectate.com.data.localfallback

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList

/** No network: records transport effects while the production listener and buffer run normally. */
internal class RecordingRealtimeSocket : WebSocket {
    val closes = CopyOnWriteArrayList<Pair<Int, String?>>()
    val sent = CopyOnWriteArrayList<String>()
    override fun request() = Request.Builder().url("https://fixture.invalid/realtime/").build()
    override fun queueSize() = 0L
    override fun send(text: String): Boolean { sent += text; return true }
    override fun send(bytes: ByteString) = true
    override fun close(code: Int, reason: String?): Boolean { closes += code to reason; return true }
    override fun cancel() = Unit
}
