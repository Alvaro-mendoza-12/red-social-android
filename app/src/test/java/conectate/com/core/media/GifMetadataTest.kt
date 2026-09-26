package conectate.com.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GifMetadataTest {
    @Test
    fun `sums the delay of every frame`() {
        val bytes = gif(frameDelaysCentiseconds = intArrayOf(50, 150))

        assertEquals(2_000L, gifSingleLoopDurationMs(bytes))
    }

    @Test
    fun `uses a safe minimum for zero-delay frames`() {
        val bytes = gif(frameDelaysCentiseconds = intArrayOf(0, 0, 0))

        assertEquals(60L, gifSingleLoopDurationMs(bytes))
    }

    @Test
    fun `uses a conservative default when a frame has no control extension`() {
        val bytes = gif(frameDelaysCentiseconds = intArrayOf(-1))

        assertEquals(100L, gifSingleLoopDurationMs(bytes))
    }

    @Test
    fun `rejects non-gif and truncated data`() {
        assertNull(gifSingleLoopDurationMs("not-a-gif".encodeToByteArray()))
        assertNull(gifSingleLoopDurationMs(gif(intArrayOf(10)).copyOf(20)))
    }

    private fun gif(frameDelaysCentiseconds: IntArray): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes += "GIF89a".encodeToByteArray().toList()
        bytes += listOf(
            1, 0, // Logical width.
            1, 0, // Logical height.
            0, 0, 0, // No global color table, background, aspect ratio.
        ).map(Int::toByte)
        frameDelaysCentiseconds.forEach { delay ->
            if (delay >= 0) {
                bytes += listOf(
                    0x21, 0xF9, 0x04, 0x00,
                    delay and 0xFF,
                    (delay ushr 8) and 0xFF,
                    0x00, 0x00,
                ).map(Int::toByte)
            }
            bytes += listOf(
                0x2C,
                0, 0, 0, 0, // Left/top.
                1, 0, 1, 0, // Width/height.
                0, // No local color table.
                2, // LZW minimum code size.
                1, 0, // One byte of image data.
                0, // Sub-block terminator.
            ).map(Int::toByte)
        }
        bytes += 0x3B.toByte()
        return bytes.toByteArray()
    }
}
