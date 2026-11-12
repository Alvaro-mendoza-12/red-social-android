package conectate.com.data.localfallback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.supabase.AuthSessionMutationGuard
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in against the disposable review116 fixture, never the configured production backend.
 * Bootstrap credentials stay in memory. TLS trusts only that fixture's certificate.
 */
@RunWith(AndroidJUnit4::class)
class Ubuntu116WireContractTest {
    private fun control(path: String): JSONObject = Socket("10.0.2.2", 43119).use { socket ->
        socket.soTimeout = 15_000
        socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
        val response = socket.getInputStream().bufferedReader().readText()
        check(response.startsWith("HTTP/1.1 200")) { "Isolated fixture unavailable" }
        JSONObject(response.substringAfter("\r\n\r\n"))
    }

    @Test(timeout = 120_000)
    fun pendingCancellationAndBufferedRealtimeSurviveTokenRefresh() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("review116Fixture") == "true")
        val fixture = control("/bootstrap")
        val actors = fixture.getJSONArray("users")
        repeat(actors.length()) {
            check(actors.getJSONObject(it).getString("id").startsWith("11600000-"))
            check(actors.getJSONObject(it).getString("email").endsWith("@example.invalid"))
        }
        val a = actors.getJSONObject(0)
        val b = actors.getJSONObject(1)
        val c = actors.getJSONObject(2)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(fixture.getString("certificate").toByteArray()),
        )
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val manager = trust.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val http = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, manager)
            .callTimeout(15, TimeUnit.SECONDS).build()
        fun request(path: String, actor: JSONObject, body: JSONObject, method: String = "POST", expected: Int = 200): JSONObject {
            val req = Request.Builder().url("https://10.0.2.2:43118$path")
                .header("Authorization", "Bearer " + actor.getString("accessToken"))
                .method(method, body.toString().toRequestBody("application/json".toMediaType())).build()
            return http.newCall(req).execute().use { response ->
                if (expected == 200) assertTrue("Fixture HTTP ${response.code}", response.isSuccessful)
                else assertEquals(expected, response.code)
                JSONObject(response.body!!.string())
            }
        }
        val first = request("/v1/data/friends", a, JSONObject().put("receiverId", c.getString("id")))
            .getJSONObject("request").getString("id")
        request("/v1/data/friends/$first/cancel", a, JSONObject())
        val second = request("/v1/data/friends", a, JSONObject().put("receiverId", c.getString("id")))
            .getJSONObject("request").getString("id")
        request("/v1/data/friends/$second", c, JSONObject().put("status", "accepted"), "PATCH")
        request("/v1/data/friends/$second/cancel", a, JSONObject(), expected = 409)

        val guard = AuthSessionMutationGuard()
        var account: String? = b.getString("id")
        var token = b.getString("accessToken")
        val realtime = UbuntuRealtimeClient(
            { guard.captureAccountLease { account } }, { token }, guard::commitForLease,
            { req, listener -> http.newWebSocket(req.newBuilder().url("https://10.0.2.2:43118/realtime/").build(), listener) },
        )
        val ready = CompletableDeferred<Unit>()
        val barrier = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val both = CompletableDeferred<Unit>()
        val secondAdmitted = CompletableDeferred<Unit>()
        val ids = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val received = mutableListOf<String>()
        fun messageId(e: UbuntuRealtimeEnvelope) = e.payload["message"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        val slow = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            realtime.events.collect { e ->
                if (e.event == "subscription_ready") ready.complete(Unit)
                val id = messageId(e)
                if (id == ids[0]) { barrier.complete(Unit); release.await() }
                if (id in ids) { received += id!!; if (received.size == 2) both.complete(Unit) }
            }
        }
        val observer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            realtime.events.collect { if (messageId(it) == ids[1]) secondAdmitted.complete(Unit) }
        }
        try {
            realtime.replaceUserSubscription(b.getString("id")); realtime.updateAccessToken(token)
            withTimeout(20_000) { ready.await() }
            fun send(id: String) = request("/v1/data/messages/chats/${fixture.getString("chatId")}/messages", a,
                JSONObject().put("id", id).put("content", "Synthetic review116 message").put("contentType", "text"))
            send(ids[0]); withTimeout(15_000) { barrier.await() }
            send(ids[1]); withTimeout(15_000) { secondAdmitted.await() }
            repeat(8) { realtime.replaceUserSubscription(b.getString("id")) }
            token = control("/rotate?actor=1").getString("accessToken")
            realtime.updateAccessToken(token)
            release.complete(Unit)
            withTimeout(15_000) { both.await() }
            assertEquals(ids, received)
        } finally {
            release.complete(Unit); slow.cancelAndJoin(); observer.cancelAndJoin()
            guard.invalidate { account = null }; realtime.clearUserSubscriptions()
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
}
