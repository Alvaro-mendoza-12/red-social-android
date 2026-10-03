package conectate.com.presentation.surveys

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.presentation.common.AppFeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.plugins.timeout
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.ByteArrayInputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Contains only the pending destination, never account credentials or responses. */
object PendingSurveyDestination {
    private const val KEY = "pendingSurveyDestination"
    fun remember(value: String, state: androidx.lifecycle.SavedStateHandle) { state[KEY] = value }
    fun consume(navController: androidx.navigation.NavController): String? = runCatching {
        navController.getBackStackEntry(conectate.com.presentation.navigation.Screen.Surveys.route)
            .savedStateHandle.remove<String>(KEY)
    }.getOrNull()
}

/** Shared by the authenticated ViewModel and instrumented HTTPS integration checks. */
internal suspend fun requestSurveyApi(client: HttpClient, baseUrl: String, token: String, input: JSONObject): JSONObject {
    val path = input.getString("path")
    val method = input.optString("method", "GET")
    require(path.startsWith("/v1/surveys/") && !path.contains("..") &&
        !path.contains('#') && !path.contains('\\') && path.length <= 2048 &&
        method in setOf("GET", "POST", "PUT", "DELETE")) { "Solicitud de encuesta inválida." }
    val response = client.request(baseUrl.trimEnd('/') + path) {
        this.method = HttpMethod.parse(method)
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $token")
        header("ngrok-skip-browser-warning", "1")
        input.optString("accountId").takeIf { it.isNotBlank() }?.let { header("X-Survey-Account", it) }
        timeout { requestTimeoutMillis = 90_000; socketTimeoutMillis = 90_000 }
        input.optJSONObject("body")?.let { setBody(it.toString()) }
    }
    val responseText = response.bodyAsText()
    val data = runCatching { JSONObject(responseText) }.getOrNull()
    if (!response.status.isSuccess()) error(surveyApiErrorMessage(
        response.status.value, path, data?.optString("message")
    ))
    return requireNotNull(data) { "El servicio de Encuestas devolvió una respuesta inválida. Inténtalo de nuevo." }
}

/** Saves Office files and images with their actual MIME type for Android document providers. */
internal class SurveyDocumentContract : ActivityResultContract<JSONObject, Uri?>() {
    override fun createIntent(context: android.content.Context, input: JSONObject): android.content.Intent {
        val requested = input.optString("mime").substringBefore(';').lowercase()
        val mime = requested.takeIf { it in setOf(
            "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv",
            "image/png", "image/svg+xml", "image/webp", "image/jpeg"
        ) } ?: "application/octet-stream"
        val name = input.optString("name", "encuesta").replace(Regex("[\\\\/\\x00-\\x1f]"), "").take(160).ifBlank { "encuesta" }
        return android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(android.content.Intent.EXTRA_TITLE, name)
        }
    }
    override fun parseResult(resultCode: Int, intent: android.content.Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

@HiltViewModel
class SurveysViewModel @Inject constructor(
    private val auth: AuthService,
    private val config: SupabaseConfig,
    private val api: LocalFallbackApiClient
) : ViewModel() {
    val session = auth.observeSession()

    fun request(payload: String, receive: (JSONObject) -> Unit) {
        viewModelScope.launch {
            var requestId = ""
            val reply = try {
                val input = JSONObject(payload)
                requestId = input.getString("id")
                val token = auth.getAccessTokenAwait() ?: error("Inicia sesión para abrir Encuestas.")
                val data = requestSurveyApi(config.httpClient, api.baseUrl, token, input)
                JSONObject().put("id", requestId).put("value", data)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                JSONObject().put("id", requestId).put("error", error.message ?: "No se pudo consultar Encuestas.")
            }
            receive(reply)
        }
    }
}

/** The bridge is exposed exclusively to packaged survey assets. Authentication stays native. */
@Keep
private class SurveysBridge(
    val requestAction: (String) -> Unit,
    val downloadAction: (String) -> Unit,
    val copyAction: (String) -> Unit,
    val shareAction: (String) -> Unit,
    val readyAction: () -> Unit,
    val closeAction: () -> Unit
) {
    @JavascriptInterface fun request(payload: String) = requestAction(payload)
    @JavascriptInterface fun download(payload: String) = downloadAction(payload)
    @JavascriptInterface fun copy(link: String) = copyAction(link)
    @JavascriptInterface fun share(link: String) = shareAction(link)
    @JavascriptInterface fun ready() = readyAction()
    @JavascriptInterface fun close() = closeAction()
}

@Composable
fun SurveysScreen(
    surveyId: String? = null,
    shareKey: String? = null,
    mode: String? = null,
    onBack: () -> Unit,
    viewModel: SurveysViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    key(session?.user?.id) {
        SurveyWorkbench(surveyId, shareKey, mode, onBack, viewModel::request)
    }
}

/** Mobile UI and native file actions; the session-aware transport is supplied by the ViewModel. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SurveyWorkbench(
    surveyId: String? = null,
    shareKey: String? = null,
    mode: String? = null,
    onBack: () -> Unit,
    request: (String, (JSONObject) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webReady by remember { mutableStateOf(false) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingDownload by remember { mutableStateOf<JSONObject?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.take(3).toTypedArray().takeIf { it.isNotEmpty() }); fileCallback = null
    }
    val saveDocument = rememberLauncherForActivityResult(SurveyDocumentContract()) { uri ->
        val download = pendingDownload; pendingDownload = null
        if (uri != null && download != null) scope.launch(Dispatchers.Main) {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(Base64.decode(download.getString("data"), Base64.DEFAULT)) }
                        ?: error("No se pudo guardar el archivo.")
                }
                AppFeedbackBus.show("Archivo de encuesta guardado.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { AppFeedbackBus.show("No se pudo guardar el archivo.") }
        }
    }
    val back = {
        if (webReady && webView != null) webView?.evaluateJavascript("window.surveyBack && window.surveyBack()", null)
        else onBack()
        Unit
    }
    BackHandler(onBack = back)
    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            webView?.apply { removeJavascriptInterface("NovvinSurveys"); stopLoading(); destroy() }
        }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopAppBar(title = { Text("Encuestas") }, navigationIcon = {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
        })
        AndroidView(modifier = Modifier.weight(1f).fillMaxSize(), factory = { ctx ->
            WebView(ctx).apply {
                webView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = true // System-selected attachments, never arbitrary filesystem paths.
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val uri = request?.url
                        val file = uri?.path?.removePrefix("/")
                        if (uri?.scheme == "https" && uri.host == "surveys.novvin.invalid" &&
                            file in setOf("index.html", "style.css", "workbench.js")) {
                            val mime = when (file) { "index.html" -> "text/html"; "style.css" -> "text/css"; else -> "application/javascript" }
                            return WebResourceResponse(mime, "UTF-8", ctx.assets.open("surveys/$file"))
                        }
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, parameters: FileChooserParams?): Boolean {
                        fileCallback?.onReceiveValue(null); fileCallback = callback
                        picker.launch(if (parameters?.acceptTypes?.any { it.contains("pdf") } == true) arrayOf("image/*", "application/pdf") else arrayOf("image/*"))
                        return true
                    }
                }
                addJavascriptInterface(SurveysBridge(
                    requestAction = { payload -> scope.launch(Dispatchers.Main) { request(payload) { reply ->
                        webView?.post { webView?.evaluateJavascript("window.surveyReceive(${reply})", null) }
                    } } },
                    downloadAction = { payload -> scope.launch(Dispatchers.Main) {
                        if (pendingDownload != null) {
                            AppFeedbackBus.show("Ya hay una descarga pendiente.")
                            return@launch
                        }
                        runCatching {
                            val result = JSONObject(payload)
                            require(result.getString("data").length <= 64 * 1024 * 1024)
                            pendingDownload = result
                            saveDocument.launch(result)
                        }.onFailure { pendingDownload = null; AppFeedbackBus.show("No se pudo preparar la descarga.") }
                    } },
                    copyAction = { link -> scope.launch(Dispatchers.Main) {
                        if (link.startsWith("conectate://survey/") && link.length <= 2048) {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Encuesta Novvin", link))
                            AppFeedbackBus.show("Enlace de encuesta copiado.")
                        }
                    } },
                    shareAction = { link -> scope.launch(Dispatchers.Main) {
                        if (link.startsWith("conectate://survey/") && link.length <= 2048) {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, "Participa en mi encuesta de Novvin: $link")
                            }
                            runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Compartir encuesta")) }
                                .onFailure { AppFeedbackBus.show("No se encontró una app para compartir el enlace.") }
                        }
                    } },
                    readyAction = { scope.launch(Dispatchers.Main) { webReady = true } },
                    closeAction = { scope.launch(Dispatchers.Main) { onBack() } }
                ), "NovvinSurveys")
                val query = listOfNotNull(surveyId?.let { "id=${Uri.encode(it)}" }, shareKey?.let { "key=${Uri.encode(it)}" }, mode?.let { "mode=${Uri.encode(it)}" }).joinToString("&")
                loadUrl("https://surveys.novvin.invalid/index.html?$query")
            }
        })
    }
}
