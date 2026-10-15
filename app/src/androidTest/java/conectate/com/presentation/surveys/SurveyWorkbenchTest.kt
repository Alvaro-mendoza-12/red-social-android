package conectate.com.presentation.surveys

import android.view.View
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real packaged Android WebView and bridge with synthetic, offline HTTP replies. */
@RunWith(AndroidJUnit4::class)
class SurveyWorkbenchTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var web: WebView
    private var saved: JSONObject? = null
    private var publication: JSONObject? = null
    private var failBoot = false
    private var closed = false
    private var response: JSONObject? = null
    private var moderatorMode = false
    private var report: JSONObject? = null
    private var moderation: JSONObject? = null

    private fun transport(payload: String, receive: (JSONObject) -> Unit) {
        val input = JSONObject(payload)
        val path = input.getString("path").removePrefix("/v1/surveys")
        val body = input.optJSONObject("body")
        val reply = JSONObject().put("id", input.getString("id"))
        if (path == "/me" && failBoot) {
            failBoot = false; receive(reply.put("error", "Fallo temporal de prueba")); return
        }
        val schema = JSONObject("""{"title":"Estudio móvil","purpose":"Consulta ficticia","contact":"qa@example.org","retention":"Durante esta prueba","consent":"Acepto participar","theme":"violet","sections":[{"id":"inicio","title":"Inicio"}],"questions":[{"id":"edad","title":"Edad","section":"inicio","type":"number","required":true,"measure":"continuous","role":"descriptive","min":18,"max":100,"options":[],"rows":[]}]}""")
        val value = when {
            path == "/me" -> JSONObject().put("id", "cuenta-prueba").put("can_review_reports", moderatorMode).put("can_moderate", moderatorMode)
            path == "/admin/reports" -> JSONObject().put("reports", org.json.JSONArray().put(JSONObject()
                .put("campaign_id", "campana-prueba").put("title", "Encuesta reportada").put("reason", "privacy")
                .put("detail", "Propósito no suficientemente claro").put("status", "pending").put("created_at", "2026-10-03T10:00:00Z")))
            path.endsWith("/moderation") -> { moderation = body; JSONObject().put("reviewed", true) }
            path.startsWith("/admin/campaigns/") -> JSONObject().put("schema", schema)
            path == "/forms" && input.getString("method") == "POST" -> {
                saved = JSONObject().put("id", "planilla-prueba").put("revision", 1).put("schema", body!!.getJSONObject("schema"))
                saved!!
            }
            path == "/forms" -> JSONObject().put("forms", org.json.JSONArray())
            path.endsWith("/publish") -> {
                publication = body
                JSONObject().put("id", "campana-prueba")
            }
            path == "/campaigns" -> JSONObject().put("campaigns", org.json.JSONArray().apply {
                if (publication != null) put(JSONObject().put("id", "campana-prueba").put("schema", saved!!.getJSONObject("schema"))
                    .put("revision", 1).put("status", "open").put("response_count", 0).put("visibility", "public").put("post_exists", true))
            })
            path.endsWith("/report") -> { report = body; JSONObject().put("reported", true) }
            path.startsWith("/campaigns/campana-prueba/results?") -> JSONObject()
                .put("total", 0).put("page", 0).put("pages", 0).put("questions", org.json.JSONArray())
                .put("responses", org.json.JSONArray()).put("timeline", org.json.JSONArray()).put("caveat", "Datos ficticios")
            path.startsWith("/campaigns/campana-prueba/responses") -> {
                response = JSONObject().put("id", "respuesta-prueba").put("answers", body!!.getJSONObject("answers"))
                    .put("edit_revision", (body.optInt("editRevision", 0) + 1))
                    .put("edit_until", "2099-10-03T10:30:00Z")
                response!!
            }
            path.startsWith("/campaigns/campana-prueba") -> JSONObject().put("id", "campana-prueba")
                .put("schema", schema).put("revision", 1).put("status", "open").put("response_count", if (response == null) 0 else 1)
                .put("response", response ?: JSONObject.NULL).put("server_time", "2026-10-03T10:00:00Z")
                .put("edit_until", "2099-10-03T10:30:00Z")
            else -> error("Unexpected synthetic route: $path")
        }
        receive(reply.put("value", value))
    }

    private fun start(mode: String? = null, surveyId: String? = null) {
        compose.setContent {
            MaterialTheme { SurveyWorkbench(mode = mode, surveyId = surveyId, onBack = { closed = true }, request = ::transport) }
        }
        onView(isAssignableFrom(WebView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
            override fun getDescription() = "Get the packaged survey WebView"
            override fun perform(uiController: UiController, view: View) { web = view as WebView }
        })
    }

    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue("Android WebView must finish JavaScript", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun awaitJs(condition: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (js("Boolean($condition)") == "true") return
            Thread.sleep(100)
        }
        fail("Mobile UI did not reach: $condition; text=${js("document.body.innerText")}")
    }

    private fun click(action: String) = js("document.querySelector('[data-action=\"$action\"]').click()")
    private fun confirm() {
        awaitJs("document.getElementById('modal').open")
        js("document.querySelector('[data-dialog=\"ok\"]').click()")
        awaitJs("!document.getElementById('modal').open && document.getElementById('app').getAttribute('aria-busy') === 'false'")
    }
    private fun capture(name: String) {
        js("window.surveyCaptureReady=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.surveyCaptureReady=true))")
        awaitJs("window.surveyCaptureReady")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.io.File(context.getExternalFilesDir(null), "survey-validation").apply { mkdirs() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var screenshot = automation.takeScreenshot()
        val deadline = android.os.SystemClock.elapsedRealtime() + 3_000L
        while (screenshot == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
            screenshot = automation.takeScreenshot()
        }
        // Some OEMs reject a whole-display capture while another retained window is secure.
        // PixelCopy still verifies this test's actual window, without capturing other apps.
        val captured = screenshot ?: copyOwnedWorkbenchWindow()
        java.io.File(folder, name).outputStream().use { captured.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        captured.recycle()
    }

    private fun copyOwnedWorkbenchWindow(): android.graphics.Bitmap {
        var activityContext: android.content.Context = web.context
        while (activityContext is android.content.ContextWrapper && activityContext !is android.app.Activity) {
            activityContext = activityContext.baseContext
        }
        val activity = requireNotNull(activityContext as? android.app.Activity)
        val ready = CountDownLatch(1)
        var result = android.view.PixelCopy.ERROR_UNKNOWN
        lateinit var bitmap: android.graphics.Bitmap
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val decor = activity.window.decorView
            bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(activity.window, bitmap, { status -> result = status; ready.countDown() },
                android.os.Handler(android.os.Looper.getMainLooper()))
        }
        if (!ready.await(3, TimeUnit.SECONDS) || result != android.view.PixelCopy.SUCCESS) {
            bitmap.recycle()
            error("Android did not capture the owned survey window: $result")
        }
        return bitmap
    }
    private fun field(path: String, value: String) = js("""(()=>{const el=document.querySelector('[data-field="$path"]');el.value=${JSONObject.quote(value)};el.dispatchEvent(new Event('input',{bubbles:true}));})()""")

    @Test fun editorSavesPublishesAndSupportsUndoOnAndroid() {
        start(mode = "create")
        awaitJs("document.querySelector('[data-field=\"title\"]')")
        capture("editor-android.png")
        field("title", "Encuesta del APK")
        click("undo")
        awaitJs("document.querySelector('[data-field=\"title\"]').value === ''")
        click("redo")
        awaitJs("document.querySelector('[data-field=\"title\"]').value === 'Encuesta del APK'")
        field("purpose", "Estudio ficticio")
        field("contact", "qa@example.org")
        field("retention", "Solo durante esta prueba")
        field("questions.0.title", "¿Qué día nos reunimos?")
        field("questions.0.options.0.label", "Sábado")
        field("questions.0.options.1.label", "Domingo")
        click("save")
        awaitJs("document.getElementById('save-status').textContent.includes('revisión 1')")
        assertEquals("Encuesta del APK", saved!!.getJSONObject("schema").getString("title"))
        assertEquals(180, saved!!.getJSONObject("schema").getInt("retentionDays"))
        click("publish")
        confirm()
        awaitJs("document.body.innerText.includes('Administrar participación')")
        capture("administracion-android.png")
        assertTrue(publication!!.getBoolean("social"))
        assertTrue(publication!!.getString("publicationKey").isNotBlank())
        assertEquals("public", publication!!.getString("visibility"))
    }

    @Test fun incompleteDraftIsRecoverableAndPublishingPointsToTheMissingField() {
        start(mode = "create")
        awaitJs("document.querySelector('[data-field=\"title\"]')")
        field("title", "Borrador sin completar")
        click("save")
        awaitJs("document.getElementById('save-status').textContent.includes('guardado en este dispositivo')")
        assertNull(saved)
        click("publish")
        awaitJs("document.activeElement.dataset.field === 'questions.0.title'")
        assertEquals("true", js("document.querySelector('[data-field=\"questions.0.title\"]').getAttribute('aria-invalid') === 'true'"))
        assertNull(publication)
        field("questions.0.title", "¿Qué horario prefieres?")
        field("questions.0.options.0.label", "Mañana")
        field("questions.0.options.1.label", "Tarde")
        click("publish")
        awaitJs("document.activeElement.dataset.field === 'purpose'")
        assertEquals("true", js("document.querySelector('[data-panel=\"publishing\"]').open"))
        assertEquals("true", js("document.getElementById('app').innerText.includes('Completa para qué usarás las respuestas.')"))
        capture("validacion-campo-android.png")
        js("document.getElementById('home').click()")
        awaitJs("document.getElementById('modal').open")
        js("document.querySelector('[data-dialog=\"ok\"]').click()")
        awaitJs("document.querySelector('[data-action=\"draft\"]') && document.getElementById('app').getAttribute('aria-busy') === 'false'")
        click("draft")
        awaitJs("document.querySelector('[data-field=\"title\"]').value === 'Borrador sin completar'")
        assertEquals("true", js("document.querySelector('[data-field=\"questions.0.options.1.label\"]').value === 'Tarde'"))
    }

    @Test fun exitDialogAndEditorRemainReadableWithLargeTextAndAtTheBottom() {
        start(mode = "create")
        awaitJs("document.querySelector('[data-field=\"title\"]')")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.settings.textZoom = 160 }
        js("window.scrollTo(0,document.body.scrollHeight)")
        awaitJs("document.documentElement.scrollHeight <= innerHeight+1 || window.scrollY > 0")
        js("document.getElementById('home').click()")
        awaitJs("document.getElementById('modal').open")
        capture("salir-dialogo-grande-android.png")
        println("SURVEY_DIALOG_BOUNDS=" + js("JSON.stringify({dialog:document.getElementById('modal').getBoundingClientRect().toJSON(),title:document.getElementById('modal-title').getBoundingClientRect().toJSON(),body:document.getElementById('modal-body').getBoundingClientRect().toJSON(),height:innerHeight,width:innerWidth,scroll:scrollY,viewport:{height:visualViewport.height,top:visualViewport.offsetTop}})"))
        awaitJs("(()=>{const d=document.getElementById('modal').getBoundingClientRect();const t=document.getElementById('modal-title').getBoundingClientRect();const b=document.getElementById('modal-body').getBoundingClientRect();return d.height>180&&d.top>=0&&d.bottom<=innerHeight+1&&t.height>20&&b.height>20&&b.top>=t.bottom})()")
        js("document.querySelector('[data-dialog=\"cancel\"]').click()")
        awaitJs("!document.getElementById('modal').open && document.getElementById('app').getAttribute('aria-busy') === 'false'")
        assertEquals("true", js("getComputedStyle(document.querySelector('header')).position === 'static' && getComputedStyle(document.querySelector('.toolbar')).position === 'static'"))
        assertEquals("true", js("document.documentElement.scrollWidth <= innerWidth+1"))
        click("publish")
        awaitJs("document.activeElement.dataset.field === 'title'")
        awaitJs("(()=>{const r=document.activeElement.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight})()")
    }

    @Test fun contactAndOptionLimitsIdentifyTheFieldAndAddingAnOptionKeepsTheEditorPosition() {
        start(mode = "create")
        awaitJs("document.querySelector('[data-field=\"title\"]')")
        field("title", "Encuesta de límites")
        field("questions.0.title", "Elige una opción")
        field("questions.0.options.0.label", "Sí")
        field("questions.0.options.1.label", "No")
        field("purpose", "Consulta ficticia de validación")
        click("publish")
        awaitJs("document.activeElement.dataset.field === 'contact'")
        assertEquals("true", js("document.querySelector('.field-error').textContent.includes('contacto del responsable')"))
        assertNull(saved)
        field("contact", "QA (mensajes en Novvin)")
        field("questions.0.options.1.label", "x".repeat(301))
        click("publish")
        awaitJs("document.activeElement.dataset.field === 'questions.0.options.1.label'")
        assertEquals("true", js("document.querySelector('.field-error').textContent.includes('máximo 300 caracteres')"))
        field("questions.0.options.1.label", "No")
        js("document.querySelector('[data-panel^=\"question-\"]').open=true")
        click("add-option")
        awaitJs("document.activeElement.dataset.field === 'questions.0.options.2.label'")
        assertEquals("true", js("document.querySelector('[data-panel^=\"question-\"]').open"))
        assertEquals("true", js("(()=>{const r=document.activeElement.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight})()"))
        field("questions.0.options.2.label", "Otro día")
        click("publish")
        awaitJs("document.getElementById('modal').open")
        awaitJs("(()=>{const d=document.getElementById('modal').getBoundingClientRect();const b=document.getElementById('modal-body').getBoundingClientRect();return d.top>=0&&d.bottom<=innerHeight+1&&b.height>20})()")
        assertEquals(3, saved!!.getJSONObject("schema").getJSONArray("questions").getJSONObject(0).getJSONArray("options").length())
    }

    @Test fun participantReportsBeforeConsentingWithoutSharingAnswers() {
        start(surveyId = "campana-prueba")
        awaitJs("document.querySelector('[data-action=\"report-survey\"]')")
        click("report-survey")
        awaitJs("document.getElementById('report-detail')")
        js("document.getElementById('report-detail').value='Propósito de investigación no suficientemente claro'")
        confirm()
        awaitJs("document.getElementById('notice').textContent.includes('Reporte recibido')")
        assertEquals("privacy", report!!.getString("reason"))
        assertFalse(report!!.has("answers"))
        assertNull(response)
        assertEquals("false", js("document.getElementById('consent').checked"))
    }

    @Test fun moderatorInspectsInstrumentAndRecordsDecisionOnAndroid() {
        moderatorMode = true
        start()
        awaitJs("document.querySelector('[data-action=\"review-reports\"]')")
        click("review-reports")
        awaitJs("document.body.innerText.includes('Encuesta reportada') && document.getElementById('app').getAttribute('aria-busy') === 'false'")
        click("review-instrument")
        awaitJs("document.getElementById('modal').open && document.getElementById('modal-body').innerText.includes('Edad')")
        assertEquals("true", js("document.getElementById('app').getAttribute('aria-busy') === 'true'"))
        confirm()
        awaitJs("!document.getElementById('modal').open && document.getElementById('app').getAttribute('aria-busy') !== 'true'")
        click("moderation-block")
        awaitJs("document.getElementById('moderation-note')")
        js("document.getElementById('moderation-note').value='Revisar consentimiento y propósito antes de aceptar participantes'")
        confirm()
        awaitJs("document.body.innerText.includes('Reportes de encuestas') && !document.getElementById('modal').open")
        assertEquals("block", moderation!!.getString("action"))
        assertFalse(moderation!!.has("answers"))
    }

    @Test fun participantConsentsSubmitsAndEditsSameResponseOnAndroid() {
        start(surveyId = "campana-prueba")
        awaitJs("document.getElementById('consent')")
        capture("consentimiento-android.png")
        click("start-answer")
        assertNull(response)
        assertEquals("true", js("Boolean(document.getElementById('consent'))"))
        js("document.getElementById('consent').checked=true")
        click("start-answer")
        awaitJs("document.querySelector('[data-answer=\"edad\"]')")
        js("""(()=>{const el=document.querySelector('[data-answer="edad"]');el.value='25';el.dispatchEvent(new Event('input',{bubbles:true}));})()""")
        click("answer-next")
        confirm()
        awaitJs("document.body.innerText.includes('Respuesta enviada')")
        capture("confirmacion-android.png")
        assertEquals(25, response!!.getJSONObject("answers").getInt("edad"))
        click("edit-answer")
        awaitJs("document.querySelector('[data-answer=\"edad\"]')")
        js("""(()=>{const el=document.querySelector('[data-answer="edad"]');el.value='26';el.dispatchEvent(new Event('input',{bubbles:true}));})()""")
        click("answer-next")
        confirm()
        awaitJs("document.body.innerText.includes('Respuesta enviada')")
        assertEquals("respuesta-prueba", response!!.getString("id"))
        assertEquals(2, response!!.getInt("edit_revision"))
        click("my-answer")
        awaitJs("document.body.innerText.includes('Mi respuesta')")
        assertEquals("true", js("document.getElementById('app').innerText.includes('26')"))
    }

    @Test fun retriesFailedBootAndClosesUsingNativeBackOnAndroid() {
        failBoot = true
        start()
        awaitJs("document.body.innerText.includes('Fallo temporal de prueba')")
        click("retry")
        awaitJs("document.querySelector('[data-action=\"new\"]')")
        js("window.surveyBack()")
        compose.waitUntil(5000) { closed }
    }
}
