package conectate.com.data.repository

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
        if (!mimeType.equals("image/webp", ignoreCase = true) || bytes.size < 16) return false
        val payload = bytes.toString(Charsets.ISO_8859_1)
        return payload.contains("ANIM") || payload.contains("ANMF")
    }
}
