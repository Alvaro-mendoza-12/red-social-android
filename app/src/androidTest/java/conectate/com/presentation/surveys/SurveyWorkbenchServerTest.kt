package conectate.com.presentation.surveys

import android.view.View
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.signaling.SignalingConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real WebView -> authenticated deployed API -> response by a second QA account.
 * Reads only supplied disposable fixtures; never changes the device owner's AuthService.
 */
@RunWith(AndroidJUnit4::class)
class SurveyWorkbenchServerTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var web: WebView
    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        var value = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { value = it; latch.countDown() }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return value
    }
    private fun awaitJs(condition: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (js("Boolean($condition)") == "true") return
            Thread.sleep(100)
        }
        fail("Survey UI did not finish the QA operation: ${js("document.getElementById('notice').textContent")}")
    }
    private fun field(path: String, value: String) = js("""(()=>{const el=[...document.querySelectorAll('[data-field]')].find(el=>el.dataset.field===${JSONObject.quote(path)});el.value=${JSONObject.quote(value)};el.dispatchEvent(new Event('input',{bubbles:true}));})()""")

    @Test fun packagedEditorPublishesAndReceivesARealResponseWithoutPostingToSocial() = runBlocking {
        assumeTrue("Explicit opt-in and supplied disposable QA accounts required",
            InstrumentationRegistry.getArguments().getString("auditSurveys") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accounts = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText())
        assertEquals(2, accounts.length())
        val client = HttpClient(OkHttp) { install(HttpTimeout); engine { config { dns(PublicBackendDns) } } }
        val title = "QA temporal · editor móvil de encuestas"
        var formId: String? = null
        var campaignId: String? = null
        suspend fun api(index: Int, path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
            val account = accounts.getJSONObject(index)
            val input = JSONObject().put("path", "/v1/surveys$path").put("method", method).put("accountId", account.getString("id"))
            body?.let { input.put("body", it) }
            return requestSurveyApi(client, SignalingConfig.BASE_URL, account.getString("token"), input)
        }
        try {
            compose.setContent {
                val scope = rememberCoroutineScope()
                MaterialTheme {
                    SurveyWorkbench(mode = "create", onBack = {}, request = { raw, receive ->
                        scope.launch {
                            val input = JSONObject(raw)
                            val reply = JSONObject().put("id", input.getString("id"))
                            try {
                                val value = requestSurveyApi(client, SignalingConfig.BASE_URL, accounts.getJSONObject(0).getString("token"), input)
                                if (input.getString("path") == "/v1/surveys/forms" && input.optString("method") == "POST") formId = value.getString("id")
                                if (input.getString("path").endsWith("/publish")) campaignId = value.getString("id")
                                receive(reply.put("value", value))
                            } catch (error: Exception) { receive(reply.put("error", error.message ?: "Solicitud QA fallida")) }
                        }
                    })
                }
            }
            onView(isAssignableFrom(WebView::class.java)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
                override fun getDescription() = "Obtain the packaged authenticated survey WebView"
                override fun perform(uiController: UiController, view: View) { web = view as WebView }
            })
            awaitJs("document.querySelector('[data-field=\"title\"]')")
            field("title", title)
            field("questions.0.title", "¿Se puede publicar desde el editor móvil?")
            field("questions.0.options.0.label", "Sí")
            field("questions.0.options.1.label", "No")
            field("purpose", "Verificar el editor con dos cuentas temporales; sin participantes humanos.")
            js("document.querySelector('[data-action=\"publish\"]').click()")
            awaitJs("document.getElementById('modal').open")
            js("document.getElementById('publish-visibility').value='public';document.getElementById('publish-social').checked=false;document.querySelector('[data-dialog=\"ok\"]').click()")
            awaitJs("document.body.innerText.includes('Administrar participación') && document.getElementById('app').getAttribute('aria-busy') === 'false'")
            val campaign = api(1, "/campaigns/${requireNotNull(campaignId)}")
            assertEquals(title, campaign.getJSONObject("schema").getString("title"))
            val question = campaign.getJSONObject("schema").getJSONArray("questions").getJSONObject(0)
            val answer = api(1, "/campaigns/$campaignId/responses", "POST", JSONObject().put("revision", campaign.getInt("revision"))
                .put("consent", true).put("answers", JSONObject().put(question.getString("id"), question.getJSONArray("options").getJSONObject(0).getString("id"))))
            assertTrue(answer.getString("id").isNotBlank())
            assertEquals(1, api(0, "/campaigns/$campaignId/results").getInt("total"))
            val mine = api(0, "/campaigns").getJSONArray("campaigns")
            val row = (0 until mine.length()).map { mine.getJSONObject(it) }.single { it.getString("id") == campaignId }
            assertFalse("The QA survey must never create a Social post", row.getBoolean("post_exists"))
            val folder = File(context.getExternalFilesDir(null), "survey-validation").apply { mkdirs() }
            File(folder, "server-editor-audit.json").writeText(JSONObject().put("published", true).put("responded", true)
                .put("responseCount", 1).put("socialPostCreated", false).toString())
        } finally {
            try {
                campaignId?.let { id ->
                    api(0, "/campaigns/$id/state", "POST", JSONObject().put("action", "trash"))
                    assertTrue(api(0, "/campaigns/$id/state", "POST", JSONObject().put("action", "purge").put("confirm", title)).getBoolean("deleted"))
                }
                formId?.let { id ->
                    api(0, "/forms/$id/state", "POST", JSONObject().put("action", "trash"))
                    assertTrue(api(0, "/forms/$id/state", "POST", JSONObject().put("action", "purge").put("confirm", title)).getBoolean("deleted"))
                }
            } finally { client.close() }
        }
    }
}
