package conectate.com.core.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BoundedMediaCopyTest {
    @Test fun preservesBytesAtExactLimit() {
        val bytes = ByteArray(70_000) { (it % 256).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyMediaWithLimit(ByteArrayInputStream(bytes), output, bytes.size.toLong()))
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyDownload() {
        copyMediaWithLimit(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 10)
    }
    @Test fun rejectsUnannouncedOversizedStreamBeforeWritingOverflow() {
        val output = ByteArrayOutputStream()
        try {
            copyMediaWithLimit(ByteArrayInputStream(ByteArray(100_000)), output, 40_000)
            fail("Expected size rejection")
        } catch (_: IllegalArgumentException) { assertTrue(output.size() <= 40_000) }
    }
    @Test(expected = CancellationException::class) fun respectsCancellationBeforeReading() {
        copyMediaWithLimit(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 10) { throw CancellationException() }
    }
}
