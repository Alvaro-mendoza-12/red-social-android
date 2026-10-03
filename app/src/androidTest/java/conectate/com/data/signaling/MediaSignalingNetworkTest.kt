package conectate.com.data.signaling

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Opt-in probes use a private QA fixture; they never join rooms or capture media. */
@RunWith(AndroidJUnit4::class)
class MediaSignalingNetworkTest {
    private fun fixture(): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return JSONObject(context.filesDir.resolve("media-signaling-qa.json").readText())
    }

    private fun loopbackClient(data: JSONObject): OkHttpClient {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(data.getString("loopbackCertificate").toByteArray()),
        )
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("qa-loopback", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            .readTimeout(60, TimeUnit.SECONDS).build()
    }

    @Test fun publicMediaTransportsConnectWithTheAndroidJavaClient() {
        val data = fixture()
        val base = data.getString("baseUrl")
        assertEquals("https://conectate.tail9b267c.ts.net", base)
        for (scope in listOf("call", "voice", "live")) {
            for (compatible in listOf(false, true)) {
                val plan = MediaSignalingTransportPlan(compatible)
                val done = CountDownLatch(1)
                val connected = AtomicBoolean(false)
                val socket = IO.socket(base + if (scope == "voice") "" else "/$scope", IO.Options().apply {
                    forceNew = true
                    reconnection = false
                    timeout = plan.timeoutMs
                    transports = plan.transports
                    attachSignalingAffinity(scope, "qa-transport-${UUID.randomUUID()}")
                    attachBearerToken(data.getString("token"))
                })
                val start = SystemClock.elapsedRealtime()
                try {
                    socket.on(Socket.EVENT_CONNECT) { connected.set(true); done.countDown() }
                    socket.on(Socket.EVENT_CONNECT_ERROR) { done.countDown() }
                    socket.connect()
                    assertTrue("$scope connection did not finish", done.await(12, TimeUnit.SECONDS))
                    assertTrue("$scope compatible=$compatible could not connect", connected.get())
                    Log.i("MediaSignalingQA", "scope=$scope compatible=$compatible connected_ms=${SystemClock.elapsedRealtime() - start}")
                } finally { socket.off(); socket.disconnect() }
            }
        }
    }

    @Test fun blockedWebSocketFallsBackAndPreservesAuthAndAffinity() {
        val data = fixture()
        val client = loopbackClient(data)
        val current = AtomicReference<Socket?>()
        val done = CountDownLatch(1)
        val observed = AtomicReference<JSONObject?>()
        val replacements = AtomicInteger(0)
        val active = AtomicBoolean(true)
        val start = SystemClock.elapsedRealtime()
        fun connect(plan: MediaSignalingTransportPlan) {
            val gate = MediaSignalingFallback(plan)
            val socket = IO.socket(data.getString("loopbackUrl"), IO.Options().apply {
                forceNew = true
                reconnection = false
                timeout = plan.timeoutMs
                transports = plan.transports
                query = "affinity=voice%3Aqa-restricted"
                auth = mapOf("token" to "qa-loopback")
                callFactory = client
                webSocketFactory = client
            })
            current.set(socket)
            socket.on(Socket.EVENT_CONNECT_ERROR) {
                if (gate.claim(active.get() && current.get() === socket)) {
                    replacements.incrementAndGet()
                    socket.off(); socket.disconnect()
                    connect(MediaSignalingTransportPlan(pollingFallback = true))
                } else if (plan.usesPollingFallback) { done.countDown() }
            }
            socket.on("qa-observed") { args ->
                observed.set(args.firstOrNull() as? JSONObject)
                done.countDown()
            }
            socket.connect()
        }
        try {
            connect(MediaSignalingTransportPlan())
            assertTrue("Restricted connection did not finish", done.await(12, TimeUnit.SECONDS))
            assertEquals(1, replacements.get())
            assertEquals(true, observed.get()?.optBoolean("authenticated"))
            assertEquals("voice:qa-restricted", observed.get()?.optString("affinity"))
            assertEquals("polling", observed.get()?.optString("transport"))
            val elapsed = SystemClock.elapsedRealtime() - start
            assertTrue("Restricted connection still exceeded 8 seconds", elapsed < 8_000)
            Log.i("MediaSignalingQA", "restricted_network fallback_count=1 connected_ms=$elapsed")
        } finally {
            active.set(false)
            current.get()?.let { it.off(); it.disconnect() }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    @Test fun leavingBeforeTheHandshakeTimeoutDoesNotStartAnotherTransport() {
        val data = fixture()
        val client = loopbackClient(data)
        val active = AtomicBoolean(true)
        val attemptedFallback = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val plan = MediaSignalingTransportPlan()
        val gate = MediaSignalingFallback(plan)
        val socket = IO.socket(data.getString("loopbackUrl"), IO.Options().apply {
            forceNew = true
            reconnection = false
            timeout = plan.timeoutMs
            transports = plan.transports
            callFactory = client
            webSocketFactory = client
        })
        try {
            socket.on(Socket.EVENT_CONNECT_ERROR) {
                attemptedFallback.set(gate.claim(active.get()))
                done.countDown()
            }
            socket.connect()
            // A queued handshake error belongs to a session the user has already left.
            active.set(false)
            assertTrue(done.await(6, TimeUnit.SECONDS))
            assertFalse(attemptedFallback.get())
        } finally {
            socket.off(); socket.disconnect()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }
}
