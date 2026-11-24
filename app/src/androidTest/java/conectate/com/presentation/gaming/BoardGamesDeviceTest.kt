package conectate.com.presentation.gaming

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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BoardGamesDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var web: WebView
    private val roomId = "00000000-0000-4000-8000-000000000001"
    private val actions = JSONArray()

    private fun room() = JSONObject().put("room", JSONObject()
        .put("id", roomId).put("game", "chess").put("status", "playing")
        .put("seat", 0).put("revision", 1 + actions.length()).put("capacity", 2)
        .put("actions", actions).put("reactions", JSONArray()))

    private suspend fun transport(method: String, path: String, body: String): Result<String> = runCatching {
        when (method to path) {
            "GET" to "/rooms" -> JSONObject().put("rooms", JSONArray())
            "POST" to "/match" -> {
                check(JSONObject(body).getString("game") == "chess")
                room()
            }
            "GET" to "/rooms/$roomId" -> room()
            "POST" to "/rooms/$roomId/actions" -> {
                val move = JSONObject(body)
                check(move.getInt("revision") == 1 && move.getString("from") == "e2" && move.getString("to") == "e4")
                actions.put(move)
                room()
            }
            "GET" to "/ranking" -> {
                val now = Instant.now()
                JSONObject().put("serverNow", now.toString()).put("endsAt", now.plusSeconds(295).toString())
                    .put("top", JSONArray().put(JSONObject().put("name", "Ana").put("wins", 5)))
                    .put("podium", JSONArray().put(JSONObject().put("name", "Bruno").put("wins", 8)))
            }
            else -> error("Ruta inesperada de la prueba: $method $path")
        }.toString()
    }

    private fun evaluate(script: String): String {
        val completed = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { result = it; completed.countDown() }
        }
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun await(condition: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (evaluate("Boolean($condition)") == "true") return
            Thread.sleep(100)
        }
        fail("WebView no llegó al estado esperado: $condition. ${evaluate("document.body.innerText")}")
    }

    @Test
    fun playsLocalAndNetworkChessAndRendersTournament() {
        compose.setContent {
            MaterialTheme { BoardGamesScreen(onBack = {}, request = ::transport) }
        }
        onView(isAssignableFrom(WebView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
            override fun getDescription() = "Ubicar el WebView de Juegos de mesa"
            override fun perform(controller: UiController, view: View) { web = view as WebView }
        })
        await("document.querySelector('[data-game=chess][aria-pressed=true]')")
        evaluate("document.getElementById('setup').requestSubmit()")
        await("document.getElementById('status').textContent.includes('Blancas')")
        evaluate("document.getElementById('open-online').click()")
        await("document.getElementById('online-status').textContent.includes('No hay salas')")
        evaluate("document.getElementById('find-room').click()")
        await("document.getElementById('status').textContent.includes('Tu turno')")
        evaluate("document.querySelector('[aria-label=\"e2, peón blancas\"]').click()")
        evaluate("document.querySelector('[aria-label=e4]').click()")
        await("document.getElementById('status').textContent.includes('Esperando jugada')")
        evaluate("document.getElementById('open-ranking').click()")
        await("document.getElementById('weekly-top').textContent.includes('Ana · 5 victorias')")
        await("document.getElementById('previous-podium').textContent.includes('Bruno · 8 victorias')")
        await("document.getElementById('ranking-period').textContent.includes('La semana termina en 4:')")
    }
}
