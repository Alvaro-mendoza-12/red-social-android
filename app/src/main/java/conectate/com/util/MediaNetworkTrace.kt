package conectate.com.util

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Debug-only numeric transport timing. Never inspect URLs, bodies or secrets. */
internal class MediaNetworkTrace(
    private val nanoTime: () -> Long = System::nanoTime,
    private val emit: (String) -> Unit = { android.util.Log.d("MediaNetworkTrace", it) },
) : EventListener() {
    private val id = ids.incrementAndGet()
    private var start = nanoTime()
    private var dnsStart: Long? = null
    private var connectStart: Long? = null
    private var tlsStart: Long? = null
    private var sentHeaders: Long? = null
    private var bodyStart: Long? = null
    private fun duration(from: Long?): Long = if (from == null) -1L
        else TimeUnit.NANOSECONDS.toMillis(nanoTime() - from).coerceAtLeast(0L)
    private fun event(name: String, fields: String = "") {
        // A diagnostic listener must never break or alter the actual request.
        runCatching { emit("call=$id event=$name elapsed_ms=${duration(start)} $fields".trim()) }
    }
    override fun callStart(call: Call) { start = nanoTime(); event("start") }
    override fun dnsStart(call: Call, domainName: String) { dnsStart = nanoTime(); event("dns_start") }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        event("dns_end", "duration_ms=${duration(dnsStart)} addresses=${inetAddressList.size}")
    }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = nanoTime(); event("connect_start")
    }
    override fun secureConnectStart(call: Call) { tlsStart = nanoTime(); event("tls_start") }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        event("tls_end", "duration_ms=${duration(tlsStart)} handshake_present=${handshake != null}")
    }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        event("connect_end", "duration_ms=${duration(connectStart)} protocol=$protocol")
    }
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        event("connect_failed", "duration_ms=${duration(connectStart)} type=${ioe.javaClass.simpleName}")
    }
    override fun connectionAcquired(call: Call, connection: Connection) {
        event("connection", "identity=${System.identityHashCode(connection)} protocol=${connection.protocol()}")
    }
    override fun requestHeadersEnd(call: Call, request: Request) {
        sentHeaders = nanoTime(); event("request_headers", "range_requested=${request.header("Range") != null}")
    }
    override fun responseHeadersEnd(call: Call, response: Response) {
        event("response_headers", "wait_ms=${duration(sentHeaders)} status=${response.code} protocol=${response.protocol}")
    }
    override fun responseBodyStart(call: Call) { bodyStart = nanoTime(); event("first_body") }
    override fun responseBodyEnd(call: Call, byteCount: Long) {
        event("body_end", "duration_ms=${duration(bodyStart)} bytes=$byteCount")
    }
    override fun responseFailed(call: Call, ioe: IOException) { event("response_failed", "type=${ioe.javaClass.simpleName}") }
    override fun callFailed(call: Call, ioe: IOException) { event("failed", "type=${ioe.javaClass.simpleName}") }
    override fun callEnd(call: Call) { event("end") }
    private companion object { val ids = AtomicLong() }
}
