package conectate.com.presentation.raffles

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.raffles.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android TLS/HTTP -> raffle production routes/service/SQL and shared JWT policy.
 * The local fixture owns synthetic rooms; no human sessions or production endpoints are used. */
@RunWith(AndroidJUnit4::class)
class RaffleApiIntegrationTest {
    @Test fun groupsAndCommunitiesPersistAuthoritativeDrawsAcrossReconnectAndRetries() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("raffle_api_url")
        assumeTrue("Requires the disposable loopback HTTPS raffle fixture", url == "https://127.0.0.1:55441")
        val certBytes = Base64.decode(requireNotNull(args.getString("raffle_cert_base64")), Base64.DEFAULT)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certBytes.inputStream())
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("raffle-qa", cert) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val manager = factory.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        fun client() = HttpClient(OkHttp) {
            install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 10000; connectTimeoutMillis = 3000 }
            engine { config { sslSocketFactory(tls.socketFactory, manager) } }
        }
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        var http = client()
        try {
            val fixture = JSONObject(http.get("$url/qa/accounts").bodyAsText())
            val accounts = fixture.getJSONObject("accounts")
            suspend fun call(role: String, path: String, body: String? = null, expected: Int = 200): String {
                val response = if (body == null) http.get("$url$path") {
                    bearerAuth(accounts.getJSONObject(role).getString("token"))
                } else http.post("$url$path") {
                    bearerAuth(accounts.getJSONObject(role).getString("token")); contentType(ContentType.Application.Json); setBody(body)
                }
                assertEquals("HTTP contract $path", expected, response.status.value)
                return response.bodyAsText()
            }
            for (kind in listOf("group", "community")) {
                val roomId = fixture.getJSONObject("rooms").getString(kind)
                val base = "/v1/data/raffles/$kind/$roomId"
                val eligible = json.decodeFromString<RaffleMemberPage>(call("owner", "$base/members"))
                assertEquals(3, eligible.participants.size)
                call("outsider", base, expected = 403)
                val memberId = accounts.getJSONObject("member").getString("id")
                val request = json.encodeToString(CreateRaffleRequest(UUID.randomUUID().toString(), "QA Android $kind",
                    memberIds = listOf(memberId), manualNames = listOf("Pedro", "Andrea"), start = true, automatic = false))
                if (kind == "group") {
                    val ownRequest = json.encodeToString(CreateRaffleRequest(UUID.randomUUID().toString(), "QA sorteo de integrante",
                        memberIds = listOf(memberId), manualNames = emptyList(), start = false, automatic = false))
                    val ownDetail = json.decodeFromString<RaffleDetail>(call("member", base, ownRequest))
                    assertTrue(ownDetail.canManage)
                    call("member", "$base/${ownDetail.raffle.id}/cancel",
                        json.encodeToString(RaffleCommandRequest(UUID.randomUUID().toString(), ownDetail.raffle.version)))
                } else call("member", base, request, 403)
                var detail = json.decodeFromString<RaffleDetail>(call("owner", base, request))
                if (kind == "group") call("member", "$base/${detail.raffle.id}/cancel",
                    json.encodeToString(RaffleCommandRequest(UUID.randomUUID().toString(), detail.raffle.version)), 403)
                assertEquals(3, detail.participants.size)
                assertEquals(2, detail.participants.count { it.kind == "MANUAL_PARTICIPANT" && it.userId == null })
                assertEquals(detail.raffle.id, json.decodeFromString<RaffleDetail>(call("owner", base, request)).raffle.id)
                delay(1200)
                val spinPath = "$base/${detail.raffle.id}/spin"
                val command = json.encodeToString(RaffleCommandRequest(UUID.randomUUID().toString(), detail.raffle.version))
                val outcomes = coroutineScope {
                    listOf(async { http.post("$url$spinPath") { bearerAuth(accounts.getJSONObject("owner").getString("token")); contentType(ContentType.Application.Json); setBody(command) }.status.value },
                        async { http.post("$url$spinPath") { bearerAuth(accounts.getJSONObject("admin").getString("token")); contentType(ContentType.Application.Json); setBody(command) }.status.value }).map { it.await() }
                }
                assertEquals(listOf(200, 409), outcomes.sorted())
                detail = json.decodeFromString(call("member", "$base/${detail.raffle.id}"))
                assertEquals(1, detail.currentRound!!.results.size)
                assertEquals(2, detail.currentRound!!.remainingParticipants().size)
                // New HTTP client reconstructs persisted state; it never replays animation or selects locally.
                http.close(); http = client()
                val recovered = json.decodeFromString<RaffleDetail>(call("member", "$base/${detail.raffle.id}"))
                assertEquals(detail.currentRound, recovered.currentRound)
                call("outsider", "$base/${detail.raffle.id}", expected = 403)
                detail = json.decodeFromString(call("admin", "$base/${detail.raffle.id}/automatic",
                    json.encodeToString(RaffleCommandRequest(UUID.randomUUID().toString(), detail.raffle.version, true))))
                val deadline = android.os.SystemClock.elapsedRealtime() + 25000
                while (detail.raffle.status != "completed" && android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(1000); detail = json.decodeFromString(call("member", "$base/${detail.raffle.id}"))
                }
                assertEquals("completed", detail.raffle.status)
                assertEquals(3, detail.currentRound!!.results.map { it.participant.id }.toSet().size)
                val previousRound = detail.currentRound!!
                detail = json.decodeFromString(call("owner", "$base/${detail.raffle.id}/new-round",
                    json.encodeToString(RaffleCommandRequest(UUID.randomUUID().toString(), detail.raffle.version, false))))
                assertEquals(2, detail.currentRound!!.number)
                assertEquals(previousRound, detail.rounds.first { it.number == 1 })
                detail = json.decodeFromString(call("owner", "$base/${detail.raffle.id}/cancel",
                    json.encodeToString(RaffleCommandRequest(UUID.randomUUID().toString(), detail.raffle.version))))
                assertEquals("cancelled", detail.raffle.status)
            }
            // Real local connection failure remains a failure; no success or winner is fabricated.
            assertTrue(runCatching { http.get("https://127.0.0.1:55442/unavailable") }.isFailure)
        } finally { http.close() }
    }
}
