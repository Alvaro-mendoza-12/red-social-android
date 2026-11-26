package conectate.com.presentation.gaming

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import conectate.com.data.gaming.PlayMatchApiException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val gameHost = "appassets.androidplatform.net"
private const val gamePage = "/board-games.html"
private val contentTypes = mapOf(
    gamePage to "text/html",
    "/board-games.css" to "text/css",
    "/board-games.js" to "text/javascript",
    "/board-online.js" to "text/javascript",
    "/board-match.js" to "text/javascript",
    "/board-rules.js" to "text/javascript",
    "/chess-1.4.0.js" to "text/javascript",
)

private fun permittedOrigin(uri: Uri): Boolean =
    uri.scheme == "https" && uri.encodedAuthority == gameHost

@Composable
@SuppressLint("SetJavaScriptEnabled")
internal fun BoardGamesScreen(
    onBack: () -> Unit,
    request: suspend (method: String, path: String, body: String) -> Result<String>,
    resumeRoomId: String? = null,
    onOpenOpponent: (roomId: String, userId: String, openChat: Boolean) -> Unit = { _, _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val openOpponent = rememberUpdatedState(onOpenOpponent)
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(PlayMatchColors.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver a Juegos", tint = PlayMatchColors.White)
            }
            Text("Juegos", color = PlayMatchColors.White, style = MaterialTheme.typography.titleMedium)
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            val uri = Uri.parse(url)
                            if (!permittedOrigin(uri) || uri.path != gamePage) return
                            (view.tag as? WebMessagePort)?.close()
                            val ports = view.createWebMessageChannel()
                            view.tag = ports[0]
                            var activeRoomId: String? = null
                            var allowedOpponents = emptySet<String>()
                            ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                                override fun onMessage(port: WebMessagePort, message: WebMessage) {
                                    if (view.tag !== port) return
                                    val envelope = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return
                                    val action = envelope.optString("action")
                                    if (action in setOf("profile", "chat")) {
                                        val roomId = envelope.optString("roomId")
                                        val userId = envelope.optString("opponentId")
                                        if (roomId == activeRoomId && userId in allowedOpponents) {
                                            openOpponent.value(roomId, userId, action == "chat")
                                        }
                                        return
                                    }
                                    val id = envelope.optInt("id")
                                    val method = envelope.optString("method")
                                    val path = envelope.optString("path")
                                    val body = envelope.optJSONObject("body")?.toString() ?: "{}"
                                    if (id <= 0 || path.length > 90) return
                                    scope.launch {
                                        val response = JSONObject().put("id", id)
                                        request(method, path, body).fold(
                                            onSuccess = { payload ->
                                                val room = runCatching { JSONObject(payload).optJSONObject("room") }.getOrNull()
                                                if (room != null) {
                                                    activeRoomId = room.optString("id")
                                                    val ownSeat = room.optInt("seat", -1)
                                                    val players = room.optJSONArray("players")
                                                    allowedOpponents = if (players == null || ownSeat < 0 || room.optString("status") == "closed") emptySet() else buildSet {
                                                        for (index in 0 until players.length()) {
                                                            val player = players.optJSONObject(index) ?: continue
                                                            val playerId = player.optString("id")
                                                            if (player.optInt("seat", -1) != ownSeat && playerId.isNotBlank()) add(playerId)
                                                        }
                                                    }
                                                }
                                                response.put("payload", payload)
                                            },
                                            onFailure = { response.put("error", (it as? PlayMatchApiException)?.code ?: "BOARD_UNAVAILABLE") },
                                        )
                                        if (view.tag === port) {
                                            runCatching { port.postMessage(WebMessage(response.toString())) }
                                        }
                                    }
                                }
                            })
                            view.postWebMessage(WebMessage("novvin-board", arrayOf(ports[1])), Uri.parse("https://$gameHost"))
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !permittedOrigin(request.url) || request.url.path != gamePage

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val path = request.url.path
                            val mime = if (permittedOrigin(request.url)) contentTypes[path] else null
                            return if (request.method == "GET" && path != null && mime != null) {
                                WebResourceResponse(
                                    mime, "UTF-8", 200, "OK",
                                    mapOf("Content-Security-Policy" to
                                        "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'"),
                                    context.assets.open(path.removePrefix("/")),
                                )
                            } else {
                                WebResourceResponse(
                                    "text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                                    ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                        }
                    }
                    val page = Uri.parse("https://$gameHost$gamePage").buildUpon().appendQueryParameter("mode", "android")
                    if (resumeRoomId != null) page.appendQueryParameter("resume", resumeRoomId)
                    loadUrl(page.build().toString())
                }
            },
            onRelease = { webView ->
                (webView.tag as? WebMessagePort)?.close()
                webView.tag = null
                webView.destroy()
            },
        )
    }
}
