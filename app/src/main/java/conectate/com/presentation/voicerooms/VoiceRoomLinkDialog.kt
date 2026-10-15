package conectate.com.presentation.voicerooms

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import conectate.com.R
import kotlinx.coroutines.delay

internal fun isVoiceRoomWebLink(link: String): Boolean = runCatching {
    val uri=Uri.parse(link)
    uri.scheme?.lowercase() in listOf("https", "http") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

/** Keeps shared links in the room, with visible loading, recovery and navigation. */
@Composable
internal fun VoiceRoomLinkDialog(link: String, onDismiss: () -> Unit) {
    var webView by remember(link) { mutableStateOf<WebView?>(null) }
    var progress by remember(link) { mutableIntStateOf(0) }
    var loadGeneration by remember(link) { mutableIntStateOf(0) }
    var failed by remember(link) { mutableStateOf(!isVoiceRoomWebLink(link)) }
    var canGoBack by remember(link) { mutableStateOf(false) }
    var currentHost by remember(link) { mutableStateOf(Uri.parse(link).host.orEmpty()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val dismissLatest by rememberUpdatedState(onDismiss)
    fun disposePreview() {
        val page = webView ?: return
        webView = null
        page.stopLoading()
        page.webChromeClient = null
        (page.parent as? android.view.ViewGroup)?.removeView(page)
        page.destroy()
    }
    DisposableEffect(lifecycle, link) {
        var dismissed = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (!dismissed && (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                    event == androidx.lifecycle.Lifecycle.Event.ON_STOP)) {
                dismissed = true
                // This is a temporary external-page preview. Destroying it on
                // dismissal stops HTML media, iframe players and Web Audio too;
                // WebView.onPause alone does not stop JavaScript playback.
                // Background recomposition can be suspended. Release on the
                // lifecycle thread immediately, before asking Compose to close.
                disposePreview()
                dismissLatest()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val failLoad = { failed=true; progress=100 }
    LaunchedEffect(link,loadGeneration,failed) {
        if (!failed) {
            delay(20_000)
            if(progress<100) { webView?.stopLoading(); failLoad() }
        }
    }
    DisposableEffect(link) {
        onDispose {
            disposePreview()
        }
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler { if(canGoBack) webView?.goBack() else onDismiss() }
        Surface(Modifier.padding(16.dp).widthIn(max=640.dp).fillMaxWidth().fillMaxHeight(0.86f),shape=RoundedCornerShape(20.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={ webView?.goBack() },enabled=canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.room_link_back))
                    }
                    Text(currentHost,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton(onClick={ failed=false; progress=0; loadGeneration++; webView?.reload() },enabled=isVoiceRoomWebLink(link)) {
                        Icon(Icons.Default.Refresh,stringResource(R.string.room_link_retry))
                    }
                    IconButton(onClick=onDismiss) { Icon(Icons.Default.Close,stringResource(R.string.room_link_close)) }
                }
                if(progress<100 && !failed) {
                    LinearProgressIndicator(progress={ progress/100f },modifier=Modifier.fillMaxWidth().testTag("room_link_loading"))
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(factory={ context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled=true
                            settings.domStorageEnabled=true
                            settings.allowFileAccess=false
                            settings.allowContentAccess=false
                            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            webChromeClient=object: WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) { progress=newProgress }
                            }
                            webViewClient=object: WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?,request: WebResourceRequest?): Boolean {
                                    val allowed=request?.url?.toString()?.let(::isVoiceRoomWebLink)==true
                                    if(!allowed && request?.isForMainFrame==true) failLoad()
                                    return !allowed
                                }
                                override fun onPageStarted(view: WebView?,url: String?,favicon: android.graphics.Bitmap?) {
                                    failed=false; progress=0; loadGeneration++
                                    currentHost=Uri.parse(url.orEmpty()).host.orEmpty()
                                    canGoBack=view?.canGoBack()==true
                                }
                                override fun onPageFinished(view: WebView?,url: String?) {
                                    progress=100; canGoBack=view?.canGoBack()==true
                                }
                                override fun onReceivedError(view: WebView?,request: WebResourceRequest?,error: WebResourceError?) {
                                    if(request?.isForMainFrame==true) failLoad()
                                }
                                override fun onReceivedHttpError(view: WebView?,request: WebResourceRequest?,errorResponse: WebResourceResponse?) {
                                    if(request?.isForMainFrame==true) failLoad()
                                }
                            }
                            webView=this
                            if(isVoiceRoomWebLink(link)) loadUrl(link)
                        }
                    },modifier=Modifier.fillMaxSize().alpha(if(failed) 0f else 1f))
                    if(failed) {
                        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.room_link_error),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("room_link_error"))
                                Text(stringResource(R.string.room_link_error_hint))
                                Button(onClick={ failed=false; progress=0; loadGeneration++; webView?.reload() },enabled=isVoiceRoomWebLink(link),modifier=Modifier.testTag("room_link_retry")) {
                                    Text(stringResource(R.string.room_link_retry))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
