package conectate.com.presentation.components

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import conectate.com.data.localfallback.UbuntuStorageUrl
import kotlin.math.max

/** Muted, lifecycle-aware video loop for visible cards and compact previews. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun LoopingVideoPreview(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resolvedUrl = remember(url, UbuntuStorageUrl.baseUrl) { UbuntuStorageUrl.rewrite(url).orEmpty() }
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var videoSize by remember(resolvedUrl) { mutableStateOf(VideoSize.UNKNOWN) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (resolvedUrl.isBlank()) return

    val player = remember(resolvedUrl) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    conectate.com.util.VideoCache.getCacheDataSourceFactory(context),
                ),
            )
            .build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = lifecycleStarted
            setMediaItem(MediaItem.fromUri(resolvedUrl))
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.stop()
            player.release()
        }
    }
    LaunchedEffect(lifecycleStarted, player) { player.playWhenReady = lifecycleStarted }

    AndroidView(
        factory = { viewContext -> TextureView(viewContext).also(player::setVideoTextureView) },
        update = player::setVideoTextureView,
        modifier = modifier.graphicsLayer {
            val sourceRatio = max(videoSize.width, 1).toFloat() / max(videoSize.height, 1).toFloat()
            val targetRatio = if (size.height > 0f) size.width / size.height else sourceRatio
            when (contentScale) {
                ContentScale.Fit -> if (sourceRatio > targetRatio) scaleY = targetRatio / sourceRatio else scaleX = sourceRatio / targetRatio
                else -> if (sourceRatio > targetRatio) scaleX = sourceRatio / targetRatio else scaleY = targetRatio / sourceRatio
            }
        },
    )
}
