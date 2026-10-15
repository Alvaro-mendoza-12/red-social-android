package conectate.com.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.graphics.scale
import java.io.File

/** Notification icons use a bounded still frame, including two-second video avatars. */
internal object NotificationAvatarDecoder {
    const val MAX_BYTES = 8L * 1024 * 1024
    private const val DIMENSION = 512
    private const val MAX_VIDEO_DURATION_MS = 3_000L

    fun decode(context: Context, bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            if (bounds.outWidth.toLong() * bounds.outHeight > 64_000_000L) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > DIMENSION) inSampleSize *= 2
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        if (!isVideoContainer(bytes)) return null
        val directory = File(context.noBackupFilesDir, "notification-avatar-decoding").apply { mkdirs() }
        val temporary = File.createTempFile("avatar-", ".media", directory)
        val retriever = MediaMetadataRetriever()
        return try {
            temporary.outputStream().use { it.write(bytes) }
            retriever.setDataSource(temporary.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            if (duration !in 1..MAX_VIDEO_DURATION_MS || width <= 0 || height <= 0 || width.toLong() * height > 16_000_000L) return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 && maxOf(width,height) > 2048) return null
            val ratio = minOf(1.0, DIMENSION.toDouble()/maxOf(width,height))
            val targetWidth = maxOf(1,(width*ratio).toInt())
            val targetHeight = maxOf(1,(height*ratio).toInt())
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
            } else retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame ?: return null
            if (maxOf(frame.width,frame.height) <= DIMENSION) frame else {
                frame.scale(targetWidth,targetHeight,true).also { if (it !== frame) frame.recycle() }
            }
        } catch (_: Exception) { null }
        finally { runCatching { retriever.release() }; temporary.delete() }
    }

    private fun isVideoContainer(bytes: ByteArray): Boolean =
        bytes.size >= 12 && (String(bytes,4,4,Charsets.US_ASCII) == "ftyp" ||
            bytes[0] == 0x1a.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xdf.toByte() && bytes[3] == 0xa3.toByte())
}
