package conectate.com.presentation.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import conectate.com.core.media.profileAvatarPreviewItem
import conectate.com.core.media.useSingleProfilePlayback

@RunWith(AndroidJUnit4::class)
class MediaPlaybackLifecycleDeviceTest {
    @get:Rule val compose=createComposeRule()
    private class Owner:LifecycleOwner {
        val registry=LifecycleRegistry(this)
        override val lifecycle:Lifecycle get()=registry
    }
    @Test fun aProfilePreviewEndsOnceAndDoesNotRestartAfterReturningFromTheBackground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "qa-avatar-once-${UUID.randomUUID()}.mp4").apply {
            instrumentation.context.assets.open("video-cache-smoke.mp4").use { input -> outputStream().use(input::copyTo) }
        }
        lateinit var player: ExoPlayer
        lateinit var owner: Owner
        var endings = 0
        val shown = mutableStateOf(true)
        instrumentation.runOnMainSync {
            owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            player = ExoPlayer.Builder(context).build().apply {
                useSingleProfilePlayback(); volume = 0f
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_ENDED) endings++ }
                })
                setMediaItem(profileAvatarPreviewItem(android.net.Uri.fromFile(file).toString(), 0, 300))
                prepare(); playWhenReady = true
            }
        }
        fun ended(): Boolean {
            var value = false
            instrumentation.runOnMainSync { value = player.playbackState == androidx.media3.common.Player.STATE_ENDED }
            return value
        }
        try {
            compose.setContent { if (shown.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PauseMediaPlayerOnBackground(player, resumeOnReturn = true)
            } }
            compose.waitUntil(10_000) { ended() }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            android.os.SystemClock.sleep(900)
            compose.runOnIdle {
                assertEquals(1, endings)
                assertEquals(androidx.media3.common.Player.STATE_ENDED, player.playbackState)
                assertFalse(player.isPlaying)
                assertTrue(player.currentPosition in 250L..450L)
            }
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            instrumentation.runOnMainSync { player.release(); owner.registry.currentState = Lifecycle.State.DESTROYED }
            file.delete()
        }
    }
    @Test fun avatarAutoplayWaitsForResumeAndRecoversWithoutOverridingAManualPause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "qa-avatar-lifecycle-${UUID.randomUUID()}.mp4").apply {
            instrumentation.context.assets.open("video-cache-smoke.mp4").use { input -> outputStream().use(input::copyTo) }
        }
        lateinit var player: ExoPlayer
        lateinit var owner: Owner
        val shown = mutableStateOf(true)
        instrumentation.runOnMainSync {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            player = ExoPlayer.Builder(context).build().apply {
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                volume = 0f
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                playWhenReady = true
                prepare()
            }
        }
        fun playing(): Boolean {
            var value = false
            instrumentation.runOnMainSync { value = player.isPlaying }
            return value
        }
        try {
            compose.setContent {
                if (shown.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    PauseMediaPlayerOnBackground(player, resumeOnReturn = true)
                }
            }
            compose.runOnIdle { assertFalse(player.playWhenReady) }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(10_000) { playing() }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { assertFalse(player.playWhenReady) }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(10_000) { playing() }
            compose.runOnIdle { player.pause(); owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.RESUMED
                assertFalse("A foreground manual pause must survive lifecycle changes", player.playWhenReady)
            }
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            instrumentation.runOnMainSync { player.release(); owner.registry.currentState = Lifecycle.State.DESTROYED }
            file.delete()
        }
    }
    @Test fun departurePausesAndRejectsLatePlaybackUntilTheUserReturns() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val samples=16_000*15
        val bytes=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity()-8).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        repeat(samples) {bytes.putShort(0)}
        val file=File(context.cacheDir,"qa-lifecycle-${UUID.randomUUID()}.wav").apply {writeBytes(bytes.array())}
        lateinit var player:ExoPlayer
        lateinit var owner:Owner
        val shown = mutableStateOf(true)
        instrumentation.runOnMainSync {
            owner=Owner().apply {registry.currentState=Lifecycle.State.RESUMED}
            player=ExoPlayer.Builder(context).build().apply {setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)));prepare()}
        }
        fun observePlayer(predicate: ExoPlayer.() -> Boolean): Boolean {
            var value=false
            instrumentation.runOnMainSync { value=player.predicate() }
            return value
        }
        try {
            compose.setContent {if (shown.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) {PauseMediaPlayerOnBackground(player)}}
            compose.runOnIdle {player.play()}
            compose.waitUntil(10_000) {observePlayer {isPlaying}}
            compose.runOnIdle {owner.registry.currentState=Lifecycle.State.STARTED}
            compose.waitUntil(3_000) {observePlayer {!playWhenReady && !isPlaying}}
            compose.runOnIdle {player.play()}
            compose.waitUntil(3_000) {observePlayer {!playWhenReady}}
            compose.runOnIdle {owner.registry.currentState=Lifecycle.State.RESUMED}
            compose.runOnIdle {assertFalse("Returning must not silently resume audio",player.playWhenReady);player.play()}
            compose.waitUntil(3_000) {observePlayer {isPlaying}}
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            instrumentation.runOnMainSync {player.release();owner.registry.currentState=Lifecycle.State.DESTROYED}
            file.delete()
        }
    }
}
