package conectate.com.core.media

private const val GIF_MIN_FRAME_DELAY_MS = 20L
private const val GIF_DEFAULT_FRAME_DELAY_MS = 100L

/**
 * Devuelve la duración de una reproducción del GIF sin decodificar sus píxeles.
 *
 * El parser sólo acepta una estructura de bloques válida, suma el delay de cada
 * frame y limita los delays cero a 20 ms para que un GIF malicioso no eluda el
 * límite de duración declarando animaciones instantáneas.
 */
internal fun gifSingleLoopDurationMs(bytes: ByteArray): Long? {
    if (bytes.size < 14 || !bytes.hasGifHeader()) return null

    val logicalScreenPacked = bytes[10].unsigned()
    var offset = 13
    if (logicalScreenPacked and 0x80 != 0) {
        val tableBytes = colorTableBytes(logicalScreenPacked)
        if (offset + tableBytes > bytes.size) return null
        offset += tableBytes
    }

    var pendingDelayMs: Long? = null
    var totalDurationMs = 0L
    var frameCount = 0

    while (offset < bytes.size) {
        when (bytes[offset].unsigned()) {
            0x21 -> {
                if (offset + 1 >= bytes.size) return null
                val extensionLabel = bytes[offset + 1].unsigned()
                offset += 2
                if (extensionLabel == 0xF9) {
                    if (offset + 5 >= bytes.size || bytes[offset].unsigned() != 4) return null
                    val delayCentiseconds = bytes[offset + 2].unsigned() or
                        (bytes[offset + 3].unsigned() shl 8)
                    if (bytes[offset + 5].unsigned() != 0) return null
                    pendingDelayMs = (delayCentiseconds * 10L).coerceAtLeast(GIF_MIN_FRAME_DELAY_MS)
                    offset += 6
                } else {
                    offset = skipGifSubBlocks(bytes, offset) ?: return null
                }
            }

            0x2C -> {
                if (offset + 9 >= bytes.size) return null
                val imagePacked = bytes[offset + 9].unsigned()
                offset += 10
                if (imagePacked and 0x80 != 0) {
                    val tableBytes = colorTableBytes(imagePacked)
                    if (offset + tableBytes > bytes.size) return null
                    offset += tableBytes
                }
                if (offset >= bytes.size) return null
                offset += 1 // LZW minimum code size.
                offset = skipGifSubBlocks(bytes, offset) ?: return null
                totalDurationMs += pendingDelayMs ?: GIF_DEFAULT_FRAME_DELAY_MS
                pendingDelayMs = null
                frameCount += 1
            }

            0x3B -> return totalDurationMs.takeIf { frameCount > 0 }
            else -> return null
        }
    }
    return null
}

private fun ByteArray.hasGifHeader(): Boolean {
    val header = byteArrayOf(0x47, 0x49, 0x46, 0x38)
    return header.indices.all { index -> this[index] == header[index] } &&
        (this[4] == 0x37.toByte() || this[4] == 0x39.toByte()) &&
        this[5] == 0x61.toByte()
}

private fun colorTableBytes(packed: Int): Int = 3 * (1 shl ((packed and 0x07) + 1))

private fun skipGifSubBlocks(bytes: ByteArray, startOffset: Int): Int? {
    var offset = startOffset
    while (offset < bytes.size) {
        val blockSize = bytes[offset].unsigned()
        offset += 1
        if (blockSize == 0) return offset
        if (offset + blockSize > bytes.size) return null
        offset += blockSize
    }
    return null
}

private fun Byte.unsigned(): Int = toInt() and 0xFF
