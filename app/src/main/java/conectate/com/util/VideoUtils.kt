package conectate.com.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

object VideoUtils {
    // Native retrievers allocate codec buffers as well as the returned bitmap.
    // Serialize optional poster extraction instead of decoding several 4K files
    // simultaneously when a batch of attachments is queued.
    private val thumbnailLock = Any()
    /**
     * Generates a thumbnail from a video URI
     * @param context Android context
     * @param videoUri URI of the video file
     * @return ByteArray of the JPEG thumbnail, or null if generation fails
     */
    fun generateVideoThumbnail(context: Context, videoUri: Uri): ByteArray? = synchronized(thumbnailLock) {
        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        var poster: Bitmap? = null
        return@synchronized try {
            retriever.setDataSource(context, videoUri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3_000L
            val frameTimeUs = (duration / 3L).coerceIn(0L, 1_000L) * 1_000L
            val dimensions = posterDimensions(width, height)
            // Decode a bounded frame directly. Decoding the full bitmap and
            // scaling afterwards could exhaust small heaps with high-resolution videos.
            bitmap = if (android.os.Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                    dimensions.first, dimensions.second)
            } else {
                retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            
            if (bitmap != null) {
                val decoded = requireNotNull(bitmap)
                val longest = maxOf(decoded.width, decoded.height)
                val preview = if (longest > 720) {
                    val scale = 720f / longest.toFloat()
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    decoded
                }
                poster = preview
                val stream = ByteArrayOutputStream()
                // Keep posters bounded in size and suitable for quick chat previews.
                preview.compress(Bitmap.CompressFormat.JPEG, 78, stream)
                stream.toByteArray()
            } else {
                android.util.Log.e("VideoUtils", "Failed to extract frame from video")
                null
            }
        } catch (_: OutOfMemoryError) {
            // A poster is optional; failure must not terminate the message send.
            android.util.Log.w("VideoUtils", "Insufficient memory for an optional video poster")
            null
        } catch (e: Exception) {
            android.util.Log.e("VideoUtils", "Error generating thumbnail", e)
            null
        } finally {
            poster?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            try {
                retriever.release()
            } catch (e: Exception) {
                android.util.Log.e("VideoUtils", "Error releasing retriever", e)
            }
        }
    }

    private fun posterDimensions(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 720 to 720
        val scale = minOf(1.0, 720.0 / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}
