package conectate.com.core.media

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedMediaReadTest {
    @Test fun exactLimitReturnsBytes() {
        val data = ByteArray(1024) { it.toByte() }
        assertArrayEquals(data, ByteArrayInputStream(data).readMediaBytesWithLimit(1024))
    }
    @Test fun oversizedUnknownStreamStopsWithoutReadingWholeInput() {
        var consumed = 0
        val infinite = object : InputStream() {
            override fun read(): Int { consumed++; return 1 }
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                bytes.fill(1, off, off+len); consumed += len; return len
            }
        }
        try { infinite.readMediaBytesWithLimit(1024); fail("must reject") }
        catch (expected: IllegalArgumentException) { assertTrue(consumed <= 32*1024) }
    }
    @Test fun emptyRejectsAndCallerUseCloses() {
        var closed = false
        val stream = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed=true; super.close() }
        }
        try { stream.use { it.readMediaBytesWithLimit(1) }; fail("must reject") }
        catch (expected: IllegalArgumentException) { assertTrue(closed) }
    }
    @Test fun cancellationPropagatesBeforeFirstRead() {
        try {
            ByteArrayInputStream(byteArrayOf(1)).readMediaBytesWithLimit(1) {
                throw java.util.concurrent.CancellationException("cancelled")
            }; fail("must cancel")
        } catch (expected: java.util.concurrent.CancellationException) { }
    }
}
