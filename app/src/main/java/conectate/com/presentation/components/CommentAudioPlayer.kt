package conectate.com.presentation.components

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import conectate.com.util.UbuntuMedia
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLDecoder

@Composable
fun CommentAudioPlayer(
    audioUrl: String,
    modifier: Modifier = Modifier
) {
    val youtubeVideoId = remember(audioUrl) { extractYouTubeVideoId(audioUrl) }
    var isPlaying by remember(audioUrl) { mutableStateOf(false) }
    var isLoading by remember(audioUrl) { mutableStateOf(false) }
    var progress by remember(audioUrl) { mutableStateOf(0f) }
    var errorMessage by remember(audioUrl) { mutableStateOf<String?>(null) }
    var showYoutubePlayer by remember(audioUrl) { mutableStateOf(false) }
    var mediaPlayer by remember(audioUrl) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(audioUrl) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying) {
                mediaPlayer?.let { mp ->
                    if (mp.duration > 0) {
                        progress = mp.currentPosition.toFloat() / mp.duration.toFloat()
                    }
                }
                delay(100)
            }
        }
    }

    fun togglePlay() {
        if (youtubeVideoId != null) {
            errorMessage = null
            showYoutubePlayer = true
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
                    UbuntuMedia.setDataSource(this, audioUrl)
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
                        errorMessage = "No se pudo reproducir este enlace."
                        mediaPlayer?.release()
                        mediaPlayer = null
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = "No se pudo reproducir este enlace."
                mediaPlayer?.release()
                mediaPlayer = null
            }
        } else {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    isPlaying = false
                } else {
                    mp.start()
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

            if (youtubeVideoId != null) {
                Text(
                    text = "Reproducir desde YouTube",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )
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

    if (showYoutubePlayer && youtubeVideoId != null) {
        YoutubeMusicDialog(
            videoId = youtubeVideoId,
            sourceUrl = audioUrl,
            onDismiss = { showYoutubePlayer = false },
            onExternalOpenFailed = {
                errorMessage = "No se encontró una aplicación para abrir YouTube."
            }
        )
    }
}

@Composable
private fun YoutubeMusicDialog(
    videoId: String,
    sourceUrl: String,
    onDismiss: () -> Unit,
    onExternalOpenFailed: () -> Unit
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Música del perfil",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                            }.onFailure { onExternalOpenFailed() }
                        }
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Abrir en YouTube")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                AndroidView(
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            webChromeClient = WebChromeClient()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadsImagesAutomatically = true
                            loadDataWithBaseURL(
                                "https://www.youtube-nocookie.com",
                                youtubeEmbedHtml(videoId),
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(androidx.compose.ui.graphics.Color.Black)
                )
            }
        }
    }
}

private fun youtubeEmbedHtml(videoId: String): String = """
    <!doctype html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <style>
          html, body, iframe { margin: 0; width: 100%; height: 100%; background: #000; border: 0; }
        </style>
      </head>
      <body>
        <iframe
          src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0"
          title="Música del perfil"
          allow="autoplay; encrypted-media; picture-in-picture"
          allowfullscreen>
        </iframe>
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
