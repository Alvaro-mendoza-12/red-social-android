package conectate.com.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player

/** Ordinary media cannot start or keep playing while its screen is in the background. */
@Composable
internal fun PauseMediaPlayerOnBackground(player: Player) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,player) {
        fun pauseUnlessResumed() {
            if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))player.pause()
        }
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP)player.pause()
        }
        val listener=object:Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) {
                if(playWhenReady)pauseUnlessResumed()
            }
        }
        lifecycle.addObserver(observer);player.addListener(listener);pauseUnlessResumed()
        onDispose {lifecycle.removeObserver(observer);player.removeListener(listener)}
    }
}
