package conectate.com.util

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ShortAudioSeekDataSourceTest {
    private class Upstream : DataSource {
        val data = ByteArray(400_000) { (it % 251).toByte() }
        var opens = 0
        var closes = 0
        var offset = 0
        var failRead = false
        var failClose = false
        var unknownLength = false
        private var spec: DataSpec? = null
        override fun open(dataSpec: DataSpec): Long {
            opens++; spec = dataSpec; offset = dataSpec.position.toInt()
            return if (unknownLength) C.LENGTH_UNSET.toLong() else (data.size - offset).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (failRead) throw IOException("Synthetic transfer failure")
            if (this.offset >= data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - this.offset, 1_024)
            data.copyInto(buffer, offset, this.offset, this.offset + count)
            this.offset += count
            return count
        }
        override fun close() {
            closes++; spec = null
            if (failClose) throw IOException("Synthetic close failure")
        }
        override fun getUri(): Uri? = spec?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = mapOf("Content-Type" to listOf("audio/wav"))
        override fun addTransferListener(transferListener: TransferListener) {}
    }
    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong(),
        token: String = "one"): DataSpec = DataSpec.Builder()
        .setUri("https://example.test/own-audio.wav").setPosition(position).setLength(length)
        .setHttpRequestHeaders(mapOf("Authorization" to token)).build()

    private fun header(source: ShortAudioSeekDataSource) {
        source.open(spec()); assertEquals(1_024, source.read(ByteArray(1_024), 0, 1_024)); source.close()
    }

    @Test fun `nearby forward seek uses the same response and exact bytes`() {
        val upstream = Upstream(); val tasks = mutableListOf<() -> Unit>()
        val source = ShortAudioSeekDataSource(upstream, tasks::add, nanoTime = { 1L })
        header(source)
        assertNull(source.uri); assertTrue(source.responseHeaders.isEmpty())
        assertEquals(397_976L, source.open(spec(2_024)))
        val bytes = ByteArray(32)
        assertEquals(32, source.read(bytes, 0, bytes.size))
        assertArrayEquals(upstream.data.copyOfRange(2_024, 2_056), bytes)
        assertEquals(1, upstream.opens)
        source.close(); assertEquals(1, upstream.closes)
    }

    @Test fun `selected bounded range respects requested end and old closer cannot close it`() {
        val upstream = Upstream(); val tasks = mutableListOf<() -> Unit>()
        val source = ShortAudioSeekDataSource(upstream, tasks::add, nanoTime = { 1L })
        header(source)
        assertEquals(128L, source.open(spec(32_044, 128)))
        tasks.forEach { it() }
        val bytes = ByteArray(256)
        assertEquals(128, source.read(bytes, 0, bytes.size))
        assertArrayEquals(upstream.data.copyOfRange(32_044, 32_172), bytes.copyOf(128))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, bytes.size))
        assertEquals(1, upstream.opens)
        assertEquals(0, upstream.closes)
        source.close(); assertEquals(1, upstream.closes)
    }

    @Test fun `unused initial response closes on the scheduled deadline`() {
        val upstream = Upstream(); val tasks = mutableListOf<() -> Unit>()
        val source = ShortAudioSeekDataSource(upstream, tasks::add, nanoTime = { 1L })
        header(source); assertEquals(0, upstream.closes)
        tasks.single().invoke(); assertEquals(1, upstream.closes)
        source.open(spec(32_044)); assertEquals(2, upstream.opens); source.close()
    }

    @Test fun `changed credentials reopen and do not reuse bytes across identities`() {
        val upstream = Upstream(); val tasks = mutableListOf<() -> Unit>()
        val source = ShortAudioSeekDataSource(upstream, tasks::add, nanoTime = { 1L })
        header(source); source.open(spec(32_044, token = "two"))
        assertEquals(2, upstream.opens); assertEquals(1, upstream.closes)
        tasks.forEach { it() }; assertEquals(1, upstream.closes)
        source.close(); assertEquals(2, upstream.closes)
    }

    @Test fun `backward and large seeks use ordinary HTTP reopen`() {
        for (target in listOf(0L, 350_000L)) {
            val upstream = Upstream()
            val source = ShortAudioSeekDataSource(upstream, deferClose = { }, nanoTime = { 1L })
            header(source); source.open(spec(target))
            assertEquals(2, upstream.opens); source.close()
        }
    }

    @Test fun `failure while skipping releases response immediately`() {
        val upstream = Upstream(); val tasks = mutableListOf<() -> Unit>()
        val source = ShortAudioSeekDataSource(upstream, tasks::add, nanoTime = { 1L })
        header(source); upstream.failRead = true
        assertThrows(IOException::class.java) { source.open(spec(32_044)) }
        assertEquals(1, upstream.closes); source.close(); tasks.forEach { it() }
        assertEquals(1, upstream.closes)
    }

    @Test fun `unknown source size keeps unknown length and closes after reuse`() {
        val upstream = Upstream().apply { unknownLength = true }
        val source = ShortAudioSeekDataSource(upstream, deferClose = { }, nanoTime = { 1L })
        header(source); assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec(32_044)))
        source.close(); assertEquals(1, upstream.closes)
    }

    @Test fun `bounded original requests are closed immediately`() {
        val upstream = Upstream(); val tasks = mutableListOf<() -> Unit>()
        val source = ShortAudioSeekDataSource(upstream, tasks::add, nanoTime = { 1L })
        source.open(spec(length = 2_048)); source.read(ByteArray(1_024), 0, 1_024); source.close()
        assertEquals(1, upstream.closes); assertTrue(tasks.isEmpty())
    }

    @Test fun `late scheduler cannot extend reuse beyond the monotonic deadline`() {
        val upstream = Upstream(); val tasks = mutableListOf<() -> Unit>(); var now = 1L
        val source = ShortAudioSeekDataSource(upstream, tasks::add, nanoTime = { now })
        header(source); now += 251_000_000L
        source.open(spec(32_044))
        assertEquals(2, upstream.opens); assertEquals(1, upstream.closes)
        tasks.forEach { it() }; assertEquals(1, upstream.closes)
        source.close()
    }

    @Test fun `transfer failure is preserved when closing also fails`() {
        val upstream = Upstream(); val source = ShortAudioSeekDataSource(upstream, deferClose = { }, nanoTime = { 1L })
        header(source); upstream.failRead = true; upstream.failClose = true
        val failure = assertThrows(IOException::class.java) { source.open(spec(32_044)) }
        assertEquals("Synthetic transfer failure", failure.message)
        assertEquals("Synthetic close failure", failure.suppressed.single().message)
        source.close()
    }

    @Test fun `interrupted seek releases response without draining the gap`() {
        val upstream = Upstream()
        val source = ShortAudioSeekDataSource(upstream, deferClose = { }, nanoTime = { 1L })
        header(source)
        try {
            Thread.currentThread().interrupt()
            assertThrows(java.io.InterruptedIOException::class.java) { source.open(spec(32_044)) }
            assertEquals(1_024, upstream.offset)
            assertEquals(1, upstream.closes)
        } finally { Thread.interrupted(); source.close() }
    }
}
