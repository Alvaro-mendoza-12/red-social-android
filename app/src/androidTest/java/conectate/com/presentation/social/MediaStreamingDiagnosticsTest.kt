package conectate.com.presentation.social

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.util.MediaPlaybackDataSources
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import conectate.com.util.InteractiveAudioBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Before

/** Fixed timing labels only; never logs the URL, credentials or file contents. */
@RunWith(AndroidJUnit4::class)
class MediaStreamingDiagnosticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun waitForTheForegroundAudioHost() {
        compose.waitUntil(5_000) {
            val process = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(process)
            compose.activity.hasWindowFocus() && process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun profileStreamingReportsReadinessTransferAndAudioFocusWithinTenSeconds() {
        val url = InstrumentationRegistry.getArguments().getString("qaAudioUrl").orEmpty()
        assumeTrue(url.startsWith("https://") && android.net.Uri.parse(url).path.orEmpty().startsWith(
            "/storage/v1/object/public/profile-audio/0e990f73-424e-4c33-9d83-5887f995e294/"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = AtomicLong(); val firstByte = AtomicLong(-1); val ready = CountDownLatch(1)
        var playReason = -1
        val start = SystemClock.elapsedRealtime()
        lateinit var player: ExoPlayer
        val factory = DataSource.Factory {
            MediaPlaybackDataSources.streamingFactory(context).createDataSource().apply {
                addTransferListener(object : TransferListener {
                    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, count: Int) {
                        firstByte.compareAndSet(-1, SystemClock.elapsedRealtime() - start); bytes.addAndGet(count.toLong())
                    }
                })
            }
        }
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .setLoadControl(InteractiveAudioBuffer.loadControl()).build()
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) ready.countDown() }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playReason = reason }
            })
            player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play()
        }
        try {
            val succeeded = ready.await(10, TimeUnit.SECONDS)
            instrumentation.runOnMainSync {
                android.util.Log.i("QaMediaNetwork", "audio_player ready=$succeeded totalMs=${SystemClock.elapsedRealtime()-start} " +
                    "firstByteMs=${firstByte.get()} bytes=${bytes.get()} state=${player.playbackState} " +
                    "suppression=${player.playbackSuppressionReason} bufferedMs=${player.totalBufferedDuration} " +
                    "playWhenReady=${player.playWhenReady} reason=$playReason errorCode=${player.playerError?.errorCode ?: 0}")
            }
            assertTrue("Streaming audio did not start within ten seconds; inspect fixed timing labels", succeeded)
        } finally { instrumentation.runOnMainSync { player.release() } }
    }
    @Test fun ownedAudioSupportsSmallRangesAndReusesTheStreamingConnection() {
        val url = InstrumentationRegistry.getArguments().getString("qaAudioUrl").orEmpty()
        assumeTrue(url.startsWith("https://") && android.net.Uri.parse(url).path.orEmpty().startsWith(
            "/storage/v1/object/public/profile-audio/0e990f73-424e-4c33-9d83-5887f995e294/"))
        repeat(3) { sample ->
            val start = SystemClock.elapsedRealtime()
            var dnsEnd = -1L; var tcpEnd = -1L; var tlsEnd = -1L; var headersEnd = -1L
            val client = MediaPlaybackDataSources.networkClient().newBuilder().eventListener(object : EventListener() {
                override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
                    dnsEnd = SystemClock.elapsedRealtime() - start
                }
                override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
                    protocol: okhttp3.Protocol?) { tcpEnd = SystemClock.elapsedRealtime() - start }
                override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
                    tlsEnd = SystemClock.elapsedRealtime() - start
                }
                override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                    headersEnd = SystemClock.elapsedRealtime() - start
                }
            }).build()
            client.newCall(Request.Builder().url(url).header("Range", "bytes=0-32767").build()).execute().use { response ->
                assertEquals("A small audio range must not transfer the whole song", 206, response.code)
                val size = response.body!!.bytes().size
                assertEquals(32_768, size)
                android.util.Log.i("QaMediaNetwork", "audio_range sample=$sample dnsEndMs=$dnsEnd " +
                    "connectEndMs=$tcpEnd tlsEndMs=$tlsEnd headersEndMs=$headersEnd " +
                    "totalMs=${SystemClock.elapsedRealtime()-start} bytes=$size protocol=${response.protocol}")
            }
        }
    }
}
