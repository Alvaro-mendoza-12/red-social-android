package conectate.com.presentation.components

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import conectate.com.util.UbuntuMedia
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLDecoder

@Composable
fun CommentAudioPlayer(
    audioUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val youtubeVideoId = remember(audioUrl) { extractYouTubeVideoId(audioUrl) }
    var isPlaying by remember(audioUrl) { mutableStateOf(false) }
    var isLoading by remember(audioUrl) { mutableStateOf(false) }
    var progress by remember(audioUrl) { mutableStateOf(0f) }
    var errorMessage by remember(audioUrl) { mutableStateOf<String?>(null) }
    var youtubeInitialized by remember(audioUrl) { mutableStateOf(false) }
    var youtubeWebView by remember(audioUrl) { mutableStateOf<WebView?>(null) }
    var mediaPlayer by remember(audioUrl) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(audioUrl) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
            youtubeWebView = null
        }
    }

    LaunchedEffect(isPlaying, youtubeVideoId) {
        if (isPlaying && youtubeVideoId == null) {
            while (isPlaying) {
                mediaPlayer?.let { player ->
                    if (player.duration > 0) {
                        progress = player.currentPosition.toFloat() / player.duration.toFloat()
                    }
                }
                delay(100)
            }
        }
    }

    fun togglePlay() {
        if (youtubeVideoId != null) {
            errorMessage = null
            if (!youtubeInitialized) {
                youtubeInitialized = true
                isLoading = true
                isPlaying = true
            } else {
                val command = if (isPlaying) "pauseVideo()" else "playVideo()"
                youtubeWebView?.evaluateJavascript(
                    "window.novvinYoutube && window.novvinYoutube.$command",
                    null
                )
                isPlaying = !isPlaying
            }
            return
        }

        if (mediaPlayer == null) {
            try {
                isLoading = true
                errorMessage = null
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    UbuntuMedia.setDataSource(context, this, audioUrl)
                    setOnPreparedListener {
                        isLoading = false
                        start()
                        isPlaying = true
                    }
                    setOnCompletionListener {
                        isLoading = false
                        isPlaying = false
                        progress = 0f
                    }
                    setOnErrorListener { _, _, _ ->
                        isLoading = false
                        isPlaying = false
                        progress = 0f
                        errorMessage = "No se pudo reproducir este audio."
                        mediaPlayer?.release()
                        mediaPlayer = null
                        true
                    }
                    prepareAsync()
                }
            } catch (_: Exception) {
                isLoading = false
                errorMessage = "No se pudo reproducir este audio."
                mediaPlayer?.release()
                mediaPlayer = null
            }
        } else {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    isPlaying = false
                } else {
                    player.start()
                    isPlaying = true
                }
            }
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { togglePlay() },
                enabled = !isLoading,
                modifier = Modifier.size(36.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )
                if (youtubeVideoId != null) {
                    Text(
                        text = if (isLoading) "Preparando audio…" else "Audio de YouTube",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }

    // YouTube does not expose a direct audio URL. Keep its official embedded player
    // inside this composable and control it from the same audio bar, without opening
    // a dialog, another activity or a floating video window.
    if (youtubeInitialized && youtubeVideoId != null) {
        AndroidView(
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webChromeClient = WebChromeClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadsImagesAutomatically = false
                    loadDataWithBaseURL(
                        "https://www.youtube-nocookie.com",
                        youtubeAudioEmbedHtml(youtubeVideoId),
                        "text/html",
                        "UTF-8",
                        null
                    )
                    youtubeWebView = this
                    postDelayed({ isLoading = false }, 900L)
                }
            },
            update = { webView ->
                youtubeWebView = webView
                if (isPlaying) {
                    webView.evaluateJavascript(
                        "window.novvinYoutube && window.novvinYoutube.playVideo()",
                        null
                    )
                }
            },
            onRelease = { webView ->
                webView.evaluateJavascript(
                    "window.novvinYoutube && window.novvinYoutube.stopVideo()",
                    null
                )
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
                if (youtubeWebView === webView) youtubeWebView = null
            },
            modifier = Modifier.size(1.dp)
        )
    }
}

private fun youtubeAudioEmbedHtml(videoId: String): String = """
    <!doctype html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <style>
          html, body { margin: 0; width: 1px; height: 1px; overflow: hidden; background: transparent; }
          #player { width: 200px; height: 200px; }
        </style>
        <script src="https://www.youtube.com/iframe_api"></script>
      </head>
      <body>
        <div id="player"></div>
        <script>
          var player;
          function onYouTubeIframeAPIReady() {
            player = new YT.Player('player', {
              width: '200',
              height: '200',
              videoId: '$videoId',
              playerVars: { autoplay: 1, playsinline: 1, controls: 0, rel: 0 },
              events: {
                onReady: function(event) { event.target.playVideo(); }
              }
            });
          }
          window.novvinYoutube = {
            playVideo: function() { if (player && player.playVideo) player.playVideo(); },
            pauseVideo: function() { if (player && player.pauseVideo) player.pauseVideo(); },
            stopVideo: function() { if (player && player.stopVideo) player.stopVideo(); }
          };
        </script>
      </body>
    </html>
""".trimIndent()

internal fun extractYouTubeVideoId(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null
    val normalized = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    return runCatching {
        val uri = URI(normalized)
        val host = uri.host.orEmpty()
            .lowercase()
            .removePrefix("www.")
            .removePrefix("m.")
        val pathParts = uri.path.orEmpty()
            .split('/')
            .filter(String::isNotBlank)

        val candidate = when {
            host == "youtu.be" -> pathParts.firstOrNull()
            host == "youtube.com" ||
                host == "music.youtube.com" ||
                host == "youtube-nocookie.com" -> {
                when (pathParts.firstOrNull()?.lowercase()) {
                    "embed", "shorts", "live" -> pathParts.getOrNull(1)
                    else -> uri.rawQuery.orEmpty()
                        .split('&')
                        .mapNotNull { pair ->
                            val separator = pair.indexOf('=')
                            if (separator <= 0) return@mapNotNull null
                            val key = URLDecoder.decode(pair.substring(0, separator), "UTF-8")
                            if (key != "v") return@mapNotNull null
                            URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
                        }
                        .firstOrNull()
                }
            }
            else -> null
        }

        candidate
            ?.substringBefore('?')
            ?.substringBefore('&')
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
    }.getOrNull()
}
