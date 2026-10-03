package conectate.com.presentation.surveys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android HTTPS -> production survey routes -> PostgreSQL, with isolated QA JWT sessions. */
@RunWith(AndroidJUnit4::class)
class SurveyApiIntegrationTest {
    @Test fun nativeHttpsTransportPublishesRespondsEditsAndExportsWithRealJwt() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("survey_api_url")
        assumeTrue("Requires the isolated loopback HTTPS validation fixture", url == "https://127.0.0.1:55440")
        val apiUrl = requireNotNull(url)
        val cert = InstrumentationRegistry.getInstrumentation().context.assets.open("survey-validation-cert.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("survey-qa", cert) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val manager = factory.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val client = HttpClient(OkHttp) {
            install(io.ktor.client.plugins.HttpTimeout)
            engine { config { sslSocketFactory(tls.socketFactory, manager) } }
        }
        try {
            val accounts = JSONObject(client.get(apiUrl + "/qa/accounts").bodyAsText())
            suspend fun call(role: String, path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
                val account = accounts.getJSONObject(role)
                val payload = JSONObject().put("path", "/v1/surveys$path").put("method", method)
                    .put("accountId", account.getString("id"))
                body?.let { payload.put("body", it) }
                return requestSurveyApi(client, apiUrl, account.getString("token"), payload)
            }
            assertEquals(accounts.getJSONObject("owner").getString("id"), call("owner", "/me").getString("id"))
            val schema = JSONObject("""{"title":"Encuesta HTTPS Android","purpose":"Validación ficticia","contact":"qa@example.org","retention":"Solo datos de prueba","retentionDays":30,"consent":"Acepto participar","sections":[{"id":"inicio","title":"Inicio"}],"questions":[{"id":"edad","section":"inicio","type":"number","title":"Edad","required":true,"min":18,"max":100}]}""")
            val form = call("owner", "/forms", "POST", JSONObject().put("schema", schema))
            val campaign = call("owner", "/forms/${form.getString("id")}/publish", "POST", JSONObject()
                .put("revision", 1).put("publicationKey", UUID.randomUUID().toString()).put("visibility", "public").put("social", true))
            val id = campaign.getString("id")
            assertTrue(campaign.has("post_id"))
            assertEquals("open", call("respondent", "/campaigns/$id").getString("status"))
            val submitted = call("respondent", "/campaigns/$id/responses", "POST", JSONObject().put("revision", 1)
                .put("consent", true).put("answers", JSONObject().put("edad", 25)))
            val edited = call("respondent", "/campaigns/$id/responses", "POST", JSONObject().put("revision", 1)
                .put("consent", true).put("editRevision", 1).put("answers", JSONObject().put("edad", 26)))
            assertEquals(submitted.getString("id"), edited.getString("id")); assertEquals(2, edited.getInt("edit_revision"))
            assertEquals(1, call("owner", "/campaigns/$id/results").getInt("total"))
            assertTrue(runCatching { call("respondent", "/campaigns/$id/results") }.isFailure)
            for (format in listOf("csv", "xlsx", "docx", "pdf")) {
                val file = call("owner", "/campaigns/$id/export", "POST", JSONObject().put("format", format))
                assertTrue(file.getString("name").endsWith(".$format")); assertTrue(file.getString("data").length > 100)
            }
            val qr = call("owner", "/campaigns/$id/qr", "POST", JSONObject().put("format", "link"))
            assertTrue(qr.getString("link").startsWith("conectate://survey/$id?key="))
            call("owner", "/campaigns/$id/state", "POST", JSONObject().put("action", "close"))
            assertTrue(runCatching { call("respondent", "/campaigns/$id/responses", "POST", JSONObject()
                .put("revision", 1).put("consent", true).put("editRevision", 2).put("answers", JSONObject().put("edad", 27))) }.isFailure)
        } finally { client.close() }
    }
}
