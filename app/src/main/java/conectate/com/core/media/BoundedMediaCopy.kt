package conectate.com.core.media

import java.io.InputStream
import java.io.OutputStream

/** Stream media without allocating the whole download or accepting unbounded responses. */
internal fun copyMediaWithLimit(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
    checkActive: () -> Unit = {},
): Long {
    require(maxBytes > 0)
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        checkActive()
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "El archivo supera el tamaño máximo permitido." }
        output.write(buffer, 0, read)
    }
    require(total > 0L) { "El archivo recibido está vacío." }
    output.flush()
    return total
}

/** Read a small payload with a hard limit while reading, independent of provider metadata.
 * Caller owns/uses the input stream. Large media should remain on uploadFileFromUri.
 */
internal fun InputStream.readMediaBytesWithLimit(
    maxBytes: Long,
    checkActive: () -> Unit = {},
): ByteArray {
    require(maxBytes in 1L..Int.MAX_VALUE.toLong())
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 32L * 1024).toInt())
    copyMediaWithLimit(this, output, maxBytes, checkActive)
    return output.toByteArray()
}
