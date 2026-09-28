package conectate.com.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.StickerItem
import java.security.MessageDigest
import kotlin.math.abs

internal data class StickerFingerprint(val sha256: String, val visual: String?)

internal object StickerIdentity {
    fun mediaKey(url: String?): String? = UbuntuStorageUrl.stableCacheKey(
        UbuntuStorageUrl.originalObjectUrl(url)
    )?.trim()?.takeIf(String::isNotEmpty)

    fun matches(a: StickerItem, b: StickerItem): Boolean {
        val firstKey = mediaKey(a.media_url)
        if (firstKey != null && firstKey == mediaKey(b.media_url)) return true
        val firstHash = a.content_sha256
        if (!firstHash.isNullOrBlank() && firstHash == b.content_sha256) return true
        return visualMatches(a.visual_fingerprint, b.visual_fingerprint)
    }

    fun fingerprint(bytes: ByteArray): StickerFingerprint {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val mime = StickerFormatSupport.detectMimeType(bytes, null)
        val visual = if (StickerFormatSupport.isAnimated(bytes, mime)) null
            else runCatching { visualHash(bytes) }.getOrNull()
        return StickerFingerprint(sha, visual)
    }

    private fun visualMatches(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        val a = first.split(':')
        val b = second.split(':')
        if (a.size != 5 || b.size != 5) return false
        val aHash = a[0].toULongOrNull(16) ?: return false
        val bHash = b[0].toULongOrNull(16) ?: return false
        if ((aHash xor bHash).countOneBits() > 1) return false
        val aColors = a.drop(1).map { it.toIntOrNull() ?: return false }
        val bColors = b.drop(1).map { it.toIntOrNull() ?: return false }
        // The last value is aspect ratio × 1000; this avoids merging distinct
        // simple images that happen to have the same edge pattern.
        return aColors.indices.all { index ->
            abs(aColors[index] - bColors[index]) <= if (index == 3) 20 else 5
        }
    }

    private fun visualHash(bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 64 || bounds.outHeight / sample > 64) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, 9, 8, true)
        var hash = 0uL
        var red = 0
        var green = 0
        var blue = 0
        for (y in 0 until 8) {
            for (x in 0 until 9) {
                val color = scaled.getPixel(x, y)
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                if (x < 8) {
                    val next = scaled.getPixel(x + 1, y)
                    val currentLight = Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114
                    val nextLight = Color.red(next) * 299 + Color.green(next) * 587 + Color.blue(next) * 114
                    if (currentLight > nextLight) hash = hash or (1uL shl (y * 8 + x))
                }
            }
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        val count = 72
        val aspect = (bounds.outWidth.toDouble() / bounds.outHeight * 1000).toInt()
        return "${hash.toString(16)}:${red / count}:${green / count}:${blue / count}:$aspect"
    }
}
