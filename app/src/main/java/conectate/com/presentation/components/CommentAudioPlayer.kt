package conectate.com.presentation.components

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import conectate.com.util.UbuntuMedia
import kotlinx.coroutines.delay
import java.net.URI

private val supportedAudioExtensions = setOf(
    "mp3", "m4a", "mp4", "aac", "ogg", "oga", "opus", "flac", "wav", "webm"
)

/** Only local content or direct HTTPS audio is playable; web pages/embeds are not audio sources. */
internal fun isSupportedDirectAudioSource(rawUrl: String): Boolean {
    val value = rawUrl.trim()
    if (value.isBlank()) return false
    return runCatching {
        val uri = URI(value)
        when (uri.scheme?.lowercase()) {
            "content", "file", "android.resource" -> true
            "https" -> uri.path.orEmpty().substringAfterLast('.', "").lowercase() in supportedAudioExtensions
            else -> false
        }
    }.getOrDefault(false)
}

@Composable
fun CommentAudioPlayer(
    audioUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isPlaying by remember(audioUrl) { mutableStateOf(false) }
    var isLoading by remember(audioUrl) { mutableStateOf(false) }
    var progress by remember(audioUrl) { mutableStateOf(0f) }
    var errorMessage by remember(audioUrl) { mutableStateOf<String?>(null) }
    var mediaPlayer by remember(audioUrl) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(audioUrl) {
        onDispose {
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isPlaying) {
            val player = mediaPlayer ?: break
            runCatching {
                if (player.duration > 0) {
                    progress = player.currentPosition.toFloat() / player.duration.toFloat()
                }
            }
            delay(100)
        }
    }

    fun togglePlay() {
        if (!isSupportedDirectAudioSource(audioUrl)) {
            errorMessage = "Este enlace no es un archivo de audio directo compatible."
            return
        }
        val activePlayer = mediaPlayer
        if (activePlayer == null) {
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
                        seekTo(0)
                    }
                    setOnErrorListener { failedPlayer, _, _ ->
                        isLoading = false
                        isPlaying = false
                        progress = 0f
                        errorMessage = "No se pudo reproducir este audio."
                        runCatching { failedPlayer.release() }
                        if (mediaPlayer === failedPlayer) mediaPlayer = null
                        true
                    }
                    prepareAsync()
                }
            } catch (_: Exception) {
                isLoading = false
                errorMessage = "No se pudo reproducir este audio."
                runCatching { mediaPlayer?.release() }
                mediaPlayer = null
            }
            return
        }

        runCatching {
            if (activePlayer.isPlaying) {
                activePlayer.pause()
                isPlaying = false
            } else {
                activePlayer.start()
                isPlaying = true
            }
        }.onFailure {
            isPlaying = false
            errorMessage = "No se pudo reproducir este audio."
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = ::togglePlay,
                enabled = !isLoading,
                modifier = Modifier.size(36.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            )
        }
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
