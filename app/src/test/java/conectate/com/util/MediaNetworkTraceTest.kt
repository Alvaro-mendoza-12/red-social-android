package conectate.com.util

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class MediaNetworkTraceTest {
    private val request = Request.Builder().url("https://private.example/secret-path?token=secret-token")
        .header("Authorization", "Bearer secret-bearer").header("Range", "bytes=32044-").build()
    private val call = OkHttpClient().newCall(request)

    @Test fun `numeric timing distinguishes lookup connection and response wait`() {
        var now = 0L
        val logs = mutableListOf<String>()
        val trace = MediaNetworkTrace({ now }, logs::add)
        trace.callStart(call)
        trace.dnsStart(call, "private.example")
        now = 20_000_000
        trace.dnsEnd(call, "private.example", listOf(InetAddress.getByAddress(byteArrayOf(127,0,0,1))))
        val address = InetSocketAddress.createUnresolved("private.example", 443)
        trace.connectStart(call, address, Proxy.NO_PROXY)
        now = 50_000_000
        trace.secureConnectStart(call)
        now = 90_000_000
        trace.secureConnectEnd(call, null)
        trace.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2)
        trace.requestHeadersEnd(call, request)
        now = 6_090_000_000
        trace.responseHeadersEnd(call, Response.Builder().request(request).protocol(Protocol.HTTP_2).code(200).message("secret-status").build())
        trace.responseBodyStart(call)
        now = 6_110_000_000
        trace.responseBodyEnd(call, 8192)
        trace.callEnd(call)
        assertTrue(logs.any { "event=dns_end" in it && "duration_ms=20" in it })
        assertTrue(logs.any { "event=connect_end" in it && "duration_ms=70" in it })
        assertTrue(logs.any { "event=tls_end" in it && "duration_ms=40" in it })
        assertTrue(logs.any { "event=response_headers" in it && "wait_ms=6000" in it })
        assertTrue(logs.any { "event=body_end" in it && "bytes=8192" in it && "duration_ms=20" in it })
        assertFalse(logs.joinToString().contains("private.example"))
        assertFalse(logs.joinToString().contains("secret"))
    }
    @Test fun `errors do not expose server strings or credentials`() {
        val logs = mutableListOf<String>()
        val trace = MediaNetworkTrace(emit = logs::add)
        trace.callFailed(call, IOException("secret-token Bearer secret-bearer"))
        trace.responseFailed(call, IOException("https://private.example/secret-path"))
        assertTrue(logs.all { "type=IOException" in it })
        assertFalse(logs.joinToString().contains("secret"))
        assertFalse(logs.joinToString().contains("private.example"))
    }
    @Test fun `failed diagnostics cannot break the media call`() {
        val trace = MediaNetworkTrace(emit = { throw IllegalStateException("diagnostic-only") })
        trace.callStart(call)
        trace.requestHeadersEnd(call, request)
        trace.callEnd(call)
    }
}
