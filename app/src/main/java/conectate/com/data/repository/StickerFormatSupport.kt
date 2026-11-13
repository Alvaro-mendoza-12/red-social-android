package conectate.com.data.repository

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

internal object StickerFormatSupport {
    fun detectMimeType(bytes: ByteArray, resolverMimeType: String?): String {
        val detected = when {
            bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
            bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes.copyOfRange(1, 4).toString(Charsets.US_ASCII) == "PNG" -> "image/png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            else -> null
        }
        if (detected != null) return detected

        return resolverMimeType
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.startsWith("image/") && it != "image/*" }
            ?: "image/png"
    }

    fun isAnimated(bytes: ByteArray, mimeType: String): Boolean {
        if (mimeType.equals("image/gif", ignoreCase = true)) return true
        return animatedMime(bytes.inputStream()) != null
    }

    // Read container headers only. Compressed pixel data can also contain the letters "ANIM".
    fun animatedMime(input: InputStream): String? {
        val data = DataInputStream(input.buffered())
        return try {
            when (data.readInt()) {
                0x47494638 -> when (data.readUnsignedShort()) {
                    0x3761, 0x3961 -> "image/gif"
                    else -> null
                }
                0x52494646 -> {
                    data.readInt() // RIFF length
                    if (data.readInt() != 0x57454250) return null // WEBP
                    when (data.readInt()) {
                        0x414e494d, 0x414e4d46 -> "image/webp" // ANIM, ANMF
                        0x56503858 -> { // VP8X, little-endian length then feature flags
                            if (Integer.reverseBytes(data.readInt()) == 10 && data.readUnsignedByte() and 2 != 0) "image/webp" else null
                        }
                        else -> null
                    }
                }
                0x89504e47.toInt() -> {
                    if (data.readInt() != 0x0d0a1a0a) return null
                    var remaining = 8 * 1024 * 1024
                    while (remaining >= 12) {
                        val length = data.readInt()
                        val type = data.readInt()
                        if (length !in 0..remaining - 12) return null
                        if (type == 0x6163544c && length == 8 && data.readInt() > 0) return "image/png" // acTL
                        if (type == 0x49444154 || type == 0x49454e44) return null // IDAT, IEND
                        if (data.skipBytes(length + 4) != length + 4) return null
                        remaining -= length + 12
                    }
                    null
                }
                else -> null
            }
        } catch (_: EOFException) {
            null
        }
    }
}
