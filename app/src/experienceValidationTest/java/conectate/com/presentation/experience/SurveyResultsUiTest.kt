package conectate.com.presentation.experience

import android.view.View
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.presentation.surveys.SurveyWorkbench
import org.hamcrest.Matcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real packaged WebView; synthetic owner and replies, no server connection. */
class SurveyResultsUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var web: WebView
    private var failNext = false
    private var rowCount = 72
    private val schema = JSONObject("""{"title":"Consulta del fin de semana","questions":[{"id":"day","title":"¿Qué día prefieres?","type":"single","measure":"nominal","role":"descriptive","options":[{"id":"sat","label":"Sábado"},{"id":"sun","label":"Domingo"}],"rows":[]},{"id":"note","title":"¿Qué sugieres?","type":"paragraph","measure":"nominal","role":"descriptive","options":[],"rows":[]}]}""")
    private fun transport(raw: String, receive: (JSONObject) -> Unit) {
        val input = JSONObject(raw); val path = input.getString("path").removePrefix("/v1/surveys")
        val reply = JSONObject().put("id", input.getString("id"))
        val value = when {
            path == "/me" -> JSONObject().put("id", "qa-owner")
            path == "/forms" -> JSONObject().put("forms", JSONArray())
            path == "/campaigns" -> JSONObject().put("campaigns", JSONArray().put(JSONObject()
                .put("id", "qa-survey").put("schema", schema).put("status", "open").put("revision", 1)
                .put("response_count", rowCount).put("visibility", "public").put("post_exists", true)))
            path.startsWith("/campaigns/qa-survey/results?") -> {
                if (failNext) { failNext = false; receive(reply.put("error", "Fallo temporal de prueba")); return }
                val uri = android.net.Uri.parse("https://qa.invalid$path")
                val page = uri.getQueryParameter("page")?.toInt() ?: 0
                val search = uri.getQueryParameter("search").orEmpty()
                val selected = (1..rowCount).filter { search.isBlank() || "Persona $it".contains(search, true) }
                val rows = JSONArray().apply { selected.drop(page * 50).take(50).forEach { n -> put(JSONObject()
                    .put("id", "qa-$n").put("account_id", "qa-account-$n").put("display_name", "Persona $n")
                    .put("submitted_at", "2026-10-08T10:00:00Z").put("updated_at", "2026-10-08T10:00:00Z").put("edit_revision", 1)
                    .put("answers", JSONObject().put("day", if (n % 2 == 0) "sun" else "sat").put("note", if (n == 1) "<img onerror='alert(1)'>" else "Sugerencia $n"))) } }
                val questions = JSONArray().apply {
                    for (i in 0 until schema.getJSONArray("questions").length()) {
                        val q = JSONObject(schema.getJSONArray("questions").getJSONObject(i).toString())
                        q.put("answered", selected.size).put("missing", 0).put("notApplicable", 0).put("histogram", JSONArray()).put("descriptive", JSONObject.NULL)
                        q.put("frequencies", JSONArray().apply { if (i == 0) {
                            put(JSONObject().put("label", "Sábado").put("absolute", selected.count { it % 2 == 1 }).put("relative", if (selected.isEmpty()) 0 else .5))
                            put(JSONObject().put("label", "Domingo").put("absolute", selected.count { it % 2 == 0 }).put("relative", if (selected.isEmpty()) 0 else .5))
                        } }); put(q)
                    }
                }
                JSONObject().put("total", selected.size).put("page", page).put("pages", (selected.size + 49) / 50)
                    .put("responses", rows).put("questions", questions).put("timeline", JSONArray()).put("caveat", "Muestra ficticia de prueba")
            }
            else -> error("Unexpected synthetic route: $path")
        }
        receive(reply.put("value", value))
    }
    private fun start() {
        compose.setContent { MaterialTheme { SurveyWorkbench(onBack = {}, request = ::transport) } }
        onView(isAssignableFrom(WebView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
            override fun getDescription() = "Get owned survey WebView"
            override fun perform(uiController: UiController, view: View) { web = view as WebView }
        })
        awaitJs("document.querySelector('[data-action=manage]')")
        click("manage"); awaitJs("document.querySelector('.response-count strong')")
    }
    private fun js(script: String): String {
        val latch = CountDownLatch(1); var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return result
    }
    private fun awaitJs(condition: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) { if (js("Boolean($condition)") == "true") return; Thread.sleep(50) }
        fail("UI did not settle: $condition; ${js("JSON.stringify({dialogOpen:document.getElementById('survey-image-viewer')?.open,imageWidth:document.querySelector('#survey-image-viewer img')?.naturalWidth,stageHeight:document.querySelector('.image-viewer-stage')?.clientHeight,dialogHeight:document.getElementById('survey-image-viewer')?.clientHeight,dvhSupported:CSS.supports('height','100dvh'),imageSource:document.querySelector('#survey-image-viewer img')?.src?.slice(0,32),viewerStyle:document.getElementById('survey-image-viewer')?getComputedStyle(document.getElementById('survey-image-viewer')).height:'absent'})")}")
    }
    private fun click(action: String) {
        js("document.querySelector('[data-action=\"$action\"]').click()")
        awaitJs("document.getElementById('app').getAttribute('aria-busy') === 'false'")
    }
    private fun capture(name: String) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "experience-audit").apply { mkdirs() }
        val result = js("JSON.stringify({width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,titleHeight:document.querySelector('h1').getBoundingClientRect().height,titleFont:getComputedStyle(document.querySelector('h1')).fontSize,text:document.getElementById('app').innerText})")
        File(folder, "$name-layout.json").writeText(result)
        // Wait for both browser paint frames and its committed compositor before PixelCopy.
        js("window.__auditPaintReady=false;requestAnimationFrame(()=>requestAnimationFrame(()=>{window.__auditPaintReady=true}))")
        awaitJs("window.__auditPaintReady === true")
        Thread.sleep(180)
        // PixelCopy captures the real WebView compositor, including charts and dark mode.
        var bitmap: android.graphics.Bitmap? = null; val done = CountDownLatch(1); var copyResult = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = generateSequence(web.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<android.app.Activity>().first()
            val view = activity.window.decorView; bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            web.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    web.postOnAnimation {
                        android.view.PixelCopy.request(activity.window, bitmap!!, { code -> copyResult = code; done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
                    }
                }
            })
        }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertEquals(android.view.PixelCopy.SUCCESS, copyResult)
        File(folder, "$name.png").outputStream().use { bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap!!.recycle()
    }
    @Test fun summaryQuestionIndividualAndPagingRemainSimple() {
        start()
        assertEquals("true", js("document.querySelector('.response-count strong').textContent === '72' && document.querySelectorAll('.result-tabs button').length === 3"))
        assertEquals("true", js("!document.querySelector('.participation-settings').open && !document.querySelector('.result-tools').open"))
        capture("survey-summary")
        click("questions"); assertEquals("1", js("document.querySelectorAll('.response-question').length")); click("question-result-next")
        assertEquals("true", js("document.querySelector('.response-question h2').textContent === '¿Qué sugieres?'"))
        click("individuals"); assertEquals("true", js("document.querySelector('.individual-response h2').textContent === 'Persona 1' && !document.querySelector('.individual-response img')"))
        for (i in 1..50) click("responses-next")
        assertEquals("true", js("document.querySelector('.individual-response h2').textContent === 'Persona 51'"))
        click("responses-prev"); assertEquals("true", js("document.querySelector('.individual-response h2').textContent === 'Persona 50'"))
        capture("survey-individual")
    }
    @Test fun largeTextFiltersEmptyStateAndRetryRemainReadable() {
        start()
        val initialHeight = js("document.querySelector('h1').getBoundingClientRect().height").toDouble()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.settings.textZoom = 160 }
        awaitJs("document.querySelector('h1').getBoundingClientRect().height >= ${initialHeight * 1.3}")
        assertEquals("true", js("document.documentElement.scrollWidth <= innerWidth + 1"))
        capture("survey-large-text")
        click("individuals")
        js("document.getElementById('filter-search').value='Persona 72'"); click("apply-result-filters")
        assertEquals("true", js("document.querySelector('.response-count strong').textContent === '1' && document.querySelector('.individual-response h2').textContent === 'Persona 72'"))
        js("document.getElementById('filter-search').value='inexistente'"); click("apply-result-filters")
        assertEquals("true", js("document.querySelector('.empty').innerText.includes('Sin respuestas')"))
        click("clear-result-filters"); failNext = true; click("results")
        awaitJs("document.querySelector('[data-action=\"retry-results\"]')"); click("retry-results")
        assertEquals("true", js("document.querySelector('.response-count strong').textContent === '72'"))
    }

    @Test fun surveyImageZoomSupportsNativePinchAndInternalControls() {
        start()
        awaitJs("typeof window.SurveyImageViewer?.enable === 'function'")
        val encoded = java.io.ByteArrayOutputStream().use { stream ->
            val picture = android.graphics.Bitmap.createBitmap(128, 64, android.graphics.Bitmap.Config.ARGB_8888)
            picture.eraseColor(android.graphics.Color.CYAN)
            picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream); picture.recycle()
            android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        }
        js("{ const t=document.createElement('img'); t.src='data:image/png;base64,$encoded'; t.alt='Foto de encuesta'; document.getElementById('app').append(t); SurveyImageViewer.enable(t); t.click(); }")
        awaitJs("document.querySelector('#survey-image-viewer img').naturalWidth > 0 && document.querySelector('.image-viewer-stage').clientHeight > 0")
        assertEquals("true", js("{const r=document.getElementById('survey-image-viewer').getBoundingClientRect(); r.left >= 0 && r.right <= innerWidth+1 && r.top >= 0 && r.bottom <= innerHeight+1}"))
        val cx = js("{const r=document.querySelector('.image-viewer-stage').getBoundingClientRect(); r.left+r.width/2}").toFloat()
        val cy = js("{const r=document.querySelector('.image-viewer-stage').getBoundingClientRect(); r.top+r.height/2}").toFloat()
        val ratio = web.width / js("innerWidth").toFloat()
        val down = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, spread: Float, count: Int) {
            val properties = Array(count) { index -> android.view.MotionEvent.PointerProperties().apply { id=index; toolType=android.view.MotionEvent.TOOL_TYPE_FINGER } }
            val positions = Array(count) { index -> android.view.MotionEvent.PointerCoords().apply { x=(cx+(if(index==0)-spread else spread))*ratio; y=cy*ratio; pressure=1f; size=1f } }
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, count, properties, positions, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { web.dispatchTouchEvent(event) }; event.recycle(); Thread.sleep(30)
        }
        send(android.view.MotionEvent.ACTION_DOWN, 40f, 1)
        send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 40f, 2)
        for (spread in listOf(50f,60f,70f,80f)) send(android.view.MotionEvent.ACTION_MOVE,spread,2)
        send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),80f,2)
        send(android.view.MotionEvent.ACTION_UP,80f,1)
        awaitJs("parseInt(document.querySelector('#survey-image-viewer output').textContent) >= 170")
        capture("survey-image-zoom")
        js("document.querySelector('[data-image-control=reset]').click()")
        assertEquals("true",js("document.querySelector('#survey-image-viewer output').textContent === '100%'"))
        js("surveyBack()")
        awaitJs("!document.getElementById('survey-image-viewer').open")
        assertEquals("true",js("Boolean(document.querySelector('.result-tabs'))"))
    }
}
