package conectate.com.util

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real VideoCache/Media3 cache, with only the network replaced by a bounded controlled source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoCachePrefetchRaceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class SlowSource(private val blockOpen: Boolean) : DataSource {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val active = AtomicBoolean(false)
        val overlappingClose = AtomicBoolean(false)
        val closes = AtomicInteger()
        private var uri: Uri? = null
        override fun addTransferListener(transferListener: TransferListener) = Unit
        private fun block() {
            active.set(true)
            entered.countDown()
            try { check(proceed.await(4, TimeUnit.SECONDS)) { "Fixture was not released" } }
            finally { active.set(false) }
        }
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            if (blockOpen) block()
            return 128
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!blockOpen) block()
            return -1
        }
        override fun getUri(): Uri? = uri
        override fun close() {
            closes.incrementAndGet()
            if (active.get()) overlappingClose.set(true)
        }
    }

    @Before fun prepare() {
        VideoCache.release()
        mockkObject(MediaPlaybackDataSources)
    }
    @After fun finish() {
        unmockkObject(MediaPlaybackDataSources)
        VideoCache.release()
    }

    private fun cancellationWhile(blockOpen: Boolean) = runBlocking {
        supervisorScope {
            val source = SlowSource(blockOpen)
            every { MediaPlaybackDataSources.prefetchFactory(any(), any()) } returns DataSource.Factory { source }
            val task = async(Dispatchers.IO) {
                runCatching { VideoCache.preloadStart(context, "https://fixture.invalid/${if (blockOpen) "open" else "read"}.mp4", 128, 5_000) }
            }
            var entered = false
            try {
                entered = withContext(Dispatchers.IO) { source.entered.await(5, TimeUnit.SECONDS) }
                assertTrue("The real cache must reach the controlled upstream", entered)
                task.cancel()
                delay(80) // Give the cancellation path time to run while open/read remains blocked.
            } finally {
                source.proceed.countDown()
                task.join()
            }
            assertTrue(entered)
            assertFalse("Closing a Media3 source concurrently releases its cache lock during open/read", source.overlappingClose.get())
            assertTrue("The loader must still close its source", source.closes.get() >= 1)
        }
    }

    @Test fun cancellationDuringOpenNeverClosesTheCacheSourceConcurrently() = cancellationWhile(true)
    @Test fun cancellationDuringReadNeverClosesTheCacheSourceConcurrently() = cancellationWhile(false)

    @Test fun cancellingOnePreloadOnlyInterruptsItsOwnHttpRequests() = runBlocking {
        supervisorScope {
            val client = mockk<OkHttpClient>()
            val callbacks = ConcurrentHashMap<Call, Callback>()
            val cancelled = ConcurrentHashMap<Call, Boolean>()
            val calls = CopyOnWriteArrayList<Call>()
            val entered = CountDownLatch(2)
            every { MediaPlaybackDataSources.boundedPrefetchClient(any()) } returns client
            every { client.newCall(any()) } answers {
                val request = firstArg<Request>()
                val call = mockk<Call>()
                every { call.request() } returns request
                every { call.isCanceled() } answers { cancelled[call] == true }
                every { call.enqueue(any()) } answers { callbacks[call] = firstArg(); entered.countDown() }
                every { call.cancel() } answers {
                    cancelled[call] = true
                    callbacks.remove(call)?.onFailure(call, java.io.IOException("Fixture cancelled"))
                }
                calls += call
                call
            }
            val first = MediaPlaybackDataSources.prefetchFactory(context, 1_000)
            val second = MediaPlaybackDataSources.prefetchFactory(context, 1_000)
            fun load(factory: DataSource.Factory) = async(Dispatchers.IO) {
                val source = factory.createDataSource()
                try { runCatching { source.open(DataSpec(Uri.parse("https://fixture.invalid/video.mp4"))) } }
                finally { source.close() }
            }
            val firstLoad = load(first)
            // Record which Call belongs to the first factory before starting the other.
            withTimeout(5_000) { while (calls.isEmpty()) delay(1) }
            val firstCall = calls.single()
            val secondLoad = load(second)
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                (first as PrefetchNetworkCancellation).cancelNetworkRequests()
                assertTrue(firstCall.isCanceled())
                assertFalse("Another media request must retain its connection", calls.last().isCanceled())
            } finally {
                (first as PrefetchNetworkCancellation).cancelNetworkRequests()
                (second as PrefetchNetworkCancellation).cancelNetworkRequests()
                firstLoad.await()
                secondLoad.await()
            }
        }
    }

    @Test fun aSpeculativeCacheExceptionUsesTheRecoverableIoFailurePath() = runBlocking {
        val source = mockk<DataSource>(relaxed = true)
        val upstreamFailure = IllegalStateException("Fixture cache state failure")
        every { source.open(any()) } throws upstreamFailure
        every { MediaPlaybackDataSources.prefetchFactory(any(), any()) } returns DataSource.Factory { source }
        val failure = runCatching { VideoCache.preloadStart(context, "https://fixture.invalid/failure.mp4", 128, 5_000) }.exceptionOrNull()
        verify(exactly = 1) { source.open(any()) }
        assertTrue("The feed handles IOException without terminating playback", failure is java.io.IOException)
        // withContext/await may add stacktrace-recovery wrappers. Preserve the
        // original upstream failure without requiring it to be the direct cause.
        val causes = generateSequence(failure) { it.cause }.take(12).toList()
        assertTrue(
            "The injected upstream failure must remain in the cause chain: ${causes.map { it.javaClass.simpleName }}",
            causes.any { it === upstreamFailure },
        )
    }
}
