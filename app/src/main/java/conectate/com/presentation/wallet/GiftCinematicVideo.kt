package conectate.com.presentation.wallet

import android.content.Context
import android.media.AudioManager
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import conectate.com.R
import conectate.com.data.local.prefs.MessagesPreferences
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.visualKey

internal const val CINEMATIC_GIFT_VIDEO_DURATION_MS = 5_000

internal fun usesCinematicGiftVideo(
    gift: VirtualGift,
    animationsEnabled: Boolean,
    renderQuality: GiftRenderQuality,
): Boolean = animationsEnabled &&
    renderQuality != GiftRenderQuality.LOW &&
    gift.priceDiamonds >= 900 &&
    gift.visualKey() == "celestial-dragon"

/** Reproduce una vez la escena MP4 local y libera el decodificador al salir. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun GiftCinematicVideo(
    runKey: Any,
    modifier: Modifier = Modifier,
    onPlaybackError: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPlaybackError by rememberUpdatedState(onPlaybackError)
    var attachedPlayer by remember(runKey) { mutableStateOf<ExoPlayer?>(null) }

    val player = remember(appContext, runKey) {
        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val soundEnabled = MessagesPreferences.areSocialSoundsEnabled(appContext) &&
                audioManager != null &&
                audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0

            ExoPlayer.Builder(appContext).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                volume = if (soundEnabled) 0.38f else 0f
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        latestPlaybackError()
                    }
                })
                setMediaItem(
                    MediaItem.fromUri(
                        RawResourceDataSource.buildRawResourceUri(R.raw.gift_celestial_dragon)
                    )
                )
                prepare()
                playWhenReady = true
            }
        }.getOrNull()
    }

    if (player == null) {
        DisposableEffect(runKey) {
            latestPlaybackError()
            onDispose { }
        }
        return
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> player.pause()
                Lifecycle.Event.ON_START -> if (player.playbackState != Player.STATE_ENDED) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            player.playbackState != Player.STATE_ENDED
        ) {
            player.play()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { videoContext ->
            TextureView(videoContext).apply {
                player.setVideoTextureView(this)
                attachedPlayer = player
            }
        },
        update = { textureView ->
            if (attachedPlayer !== player) {
                player.setVideoTextureView(textureView)
                attachedPlayer = player
            }
        }
    )
}
