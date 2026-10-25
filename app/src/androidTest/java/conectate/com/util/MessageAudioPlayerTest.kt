package conectate.com.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MessageAudioPlayerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun browserWebmWithoutDurationPlaysFromTheNativeDeliveryExtension() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Production delivery uses an opaque .m4a file; the bytes are WebM/Opus.
        val file = File.createTempFile("browser-audio-", ".m4a", context.cacheDir)
        val player = MessageAudioPlayer()
        val prepared = CountDownLatch(1)
        val failure = AtomicReference<Exception?>()
        try {
            instrumentation.context.assets.open("audio/no-duration.webm").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            main {
                player.prepare(file.absolutePath, onPrepared = {
                    if (!it.start(1f)) failure.set(IllegalStateException("Cannot start prepared WebM"))
                    prepared.countDown()
                }, onCompletion = {}, onError = { failure.set(it); prepared.countDown() })
            }
            assertTrue("Native WebM preparation timed out", prepared.await(8, TimeUnit.SECONDS))
            assertNull(failure.get())
            Thread.sleep(350)
            main {
                assertTrue("Native WebM player did not remain in playback", player.isPlaying)
                assertTrue("Position should advance when the WebM omits its duration", player.currentPosition > 0)
                assertTrue(audioPlaybackProgress(player.currentPosition.toLong(), player.duration.toLong()).isFinite())
                player.pause()
                assertFalse(player.isPlaying)
                player.setSpeed(1.5f)
                assertTrue(player.start(1.5f))
            }
        } finally { main { player.release() }; file.delete() }
    }

    @Test fun controlsBeforePreparationAndAfterReleaseAreSafe() {
        main {
            val player = MessageAudioPlayer()
            repeat(2) {
                assertFalse(player.start(1f))
                player.pause()
                assertFalse(player.seekTo(123))
                assertFalse(player.isPlaying)
                assertEquals(0, player.duration)
                assertEquals(0, player.currentPosition)
                player.setSpeed(2f)
                player.release()
            }
        }
    }

    @Test fun invalidMediaReportsOneErrorWithoutEscapingTheCallback() {
        val player = MessageAudioPlayer()
        val failures = AtomicInteger()
        val completed = CountDownLatch(1)
        main {
            player.prepare("/does-not-exist/voice.webm", onPrepared = { fail("Invalid file prepared") },
                onCompletion = { fail("Invalid file completed") }, onError = {
                    failures.incrementAndGet()
                    completed.countDown()
                })
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        main {
            assertEquals(1, failures.get())
            assertFalse(player.isPrepared)
            assertFalse(player.start(1f))
            player.release()
        }
    }

    @Test fun releaseDuringPreparationDiscardsLateCallbacks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("cancel-audio-", ".webm", context.cacheDir)
        val callbacks = AtomicInteger()
        val player = MessageAudioPlayer()
        try {
            instrumentation.context.assets.open("audio/no-duration.webm").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            main {
                player.prepare(file.absolutePath, onPrepared = { callbacks.incrementAndGet() },
                    onCompletion = { callbacks.incrementAndGet() }, onError = { callbacks.incrementAndGet() })
                player.release()
            }
            Thread.sleep(350)
            main { assertEquals(0, callbacks.get()); assertFalse(player.isPlaying) }
        } finally { main { player.release() }; file.delete() }
    }
}
