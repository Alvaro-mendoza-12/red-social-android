package conectate.com.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

object VideoUtils {
    /**
     * Generates a thumbnail from a video URI
     * @param context Android context
     * @param videoUri URI of the video file
     * @return ByteArray of the JPEG thumbnail, or null if generation fails
     */
    fun generateVideoThumbnail(context: Context, videoUri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, videoUri)
            
            // Extract frame at 1 second (1,000,000 microseconds)
            val bitmap = retriever.getFrameAtTime(
                1000000, 
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.frameAtTime
            
            if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                // Compress to JPEG with 70% quality for good balance
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                stream.toByteArray()
            } else {
                android.util.Log.e("VideoUtils", "Failed to extract frame from video")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoUtils", "Error generating thumbnail", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                android.util.Log.e("VideoUtils", "Error releasing retriever", e)
            }
        }
    }
}
