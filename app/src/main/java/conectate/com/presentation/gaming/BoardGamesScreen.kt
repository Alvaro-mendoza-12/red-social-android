package conectate.com.presentation.gaming

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

private const val gameHost = "appassets.androidplatform.net"
private const val gamePage = "/board-games.html"
private val contentTypes = mapOf(
    gamePage to "text/html",
    "/board-games.css" to "text/css",
    "/board-games.js" to "text/javascript",
    "/board-rules.js" to "text/javascript",
    "/chess-1.4.0.js" to "text/javascript",
)

private fun permittedOrigin(uri: Uri): Boolean =
    uri.scheme == "https" && uri.encodedAuthority == gameHost

@Composable
@SuppressLint("SetJavaScriptEnabled")
internal fun BoardGamesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(PlayMatchColors.Background).statusBarsPadding()) {
        TextButton(onClick = onBack) { Text("Volver a Juegos") }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
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
                    loadUrl("https://$gameHost$gamePage?mode=android")
                }
            },
            onRelease = { webView -> webView.destroy() },
        )
    }
}
