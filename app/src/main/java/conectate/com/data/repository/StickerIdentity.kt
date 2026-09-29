package conectate.com.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.StickerItem
import java.security.MessageDigest
import kotlin.math.abs

internal data class StickerFingerprint(val sha256: String, val visual: String?, val animated: Boolean = false)

internal data class StickerCanonicalState(
    val favorites: Set<String>,
    val recents: List<String>,
    val mediaAliases: Map<String, String>
)

internal object StickerIdentity {
    fun frequentFavoriteIds(recents: List<String>, favorites: Set<String>, usage: Map<String, Int>, limit: Int): List<String> =
        recents.distinct().filter { it in favorites }.sortedByDescending { usage[it] ?: 0 }.take(limit.coerceAtLeast(0))

    /** A signed revision records the latest choice, including removal during indexing. */
    fun canonicalState(
        catalog: List<StickerItem>,
        favorites: Set<String>,
        recents: List<String>,
        decisions: Map<String, Long>
    ): StickerCanonicalState {
        val groups = mutableListOf<MutableList<StickerItem>>()
        catalog.forEach { item ->
            val group = groups.firstOrNull { members -> members.any { matches(it, item) } }
            if (group == null) groups.add(mutableListOf(item)) else group.add(item)
        }
        val canonicalIds = mutableMapOf<String, String>()
        val aliases = mutableMapOf<String, String>()
        val saved = mutableSetOf<String>()
        groups.forEach { group ->
            val canonical = group.firstOrNull { it.id in favorites } ?: group.first()
            val latest = group.mapNotNull { decisions[it.id] }.maxByOrNull { abs(it) }
            val wanted = latest?.let { it > 0 } ?: group.any { it.id in favorites }
            if (wanted) saved.add(canonical.id)
            group.forEach { item ->
                canonicalIds[item.id] = canonical.id
                mediaKey(item.media_url)?.let { aliases[it] = canonical.id }
            }
        }
        return StickerCanonicalState(
            favorites = saved,
            recents = recents.mapNotNull { canonicalIds[it] }.distinct(),
            mediaAliases = aliases
        )
    }

    fun favoriteIdsToRemove(
        stickerId: String,
        catalog: List<StickerItem>,
        favorites: Set<String>
    ): List<String> {
        val target = catalog.firstOrNull { it.id == stickerId }
        if (target == null) return listOf(stickerId).filter { it in favorites }
        return catalog.filter { it.id in favorites && (it.id == stickerId || matches(it, target)) }
            .map { it.id }
    }

    fun mediaKey(url: String?): String? = UbuntuStorageUrl.stableCacheKey(
        UbuntuStorageUrl.originalObjectUrl(url)
    )?.trim()?.takeIf(String::isNotEmpty)

    fun matches(a: StickerItem, b: StickerItem): Boolean {
        val firstKey = mediaKey(a.media_url)
        if (firstKey != null && firstKey == mediaKey(b.media_url)) return true
        val firstHash = a.content_sha256
        if (!firstHash.isNullOrBlank() && firstHash == b.content_sha256) return true
        return !a.is_animated && !b.is_animated && visualMatches(a.visual_fingerprint, b.visual_fingerprint)
    }

    fun fingerprint(bytes: ByteArray): StickerFingerprint {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val mime = StickerFormatSupport.detectMimeType(bytes, null)
        val animated = StickerFormatSupport.isAnimated(bytes, mime)
        val visual = if (animated) null
            else runCatching { visualHash(bytes) }.getOrNull()
        return StickerFingerprint(sha, visual, animated)
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
