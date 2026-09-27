package conectate.com.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** The upload endpoint stores every video as MP4; inspect the bytes and tracks first. */
fun isDirectlyPublishableMp4(context: Context, uri: Uri): Boolean {
    val header = ByteArray(12)
    val read = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            var count = 0
            while (count < header.size) {
                val next = input.read(header, count, header.size - count)
                if (next < 0) break
                count += next
            }
            count
        } ?: 0
    }.getOrDefault(0)
    if (read < header.size || String(header, 4, 4, Charsets.US_ASCII) != "ftyp") return false
    if (String(header, 8, 4, Charsets.US_ASCII) == "qt  ") return false

    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        var videoFound = false
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            when {
                mime.startsWith("video/") -> {
                    if (mime != "video/avc" || videoFound) return false
                    videoFound = true
                }
                mime.startsWith("audio/") && mime != "audio/mp4a-latm" -> return false
            }
        }
        videoFound
    } catch (_: Exception) {
        false
    } finally {
        runCatching { extractor.release() }
    }
}
