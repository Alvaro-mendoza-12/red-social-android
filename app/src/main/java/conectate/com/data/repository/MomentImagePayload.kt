package conectate.com.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import conectate.com.core.media.exifOrientationMatrix
import conectate.com.util.preferredExtensionForMime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

internal data class MomentMediaPayload(val bytes: ByteArray, val contentType: String, val extension: String)

private const val MAX_CONVERTED_IMAGE_EDGE = 2048

/** Uses the original upload bytes and only decodes pixels when a conversion is necessary. */
internal fun prepareMomentImage(bytes: ByteArray): Result<MomentMediaPayload> {
    if (bytes.isEmpty()) return Result.failure(IllegalArgumentException("Archivo vacío"))

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val mime = bounds.outMimeType
    val extension = preferredExtensionForMime(mime)
    if (extension != null && bounds.outWidth > 0 && bounds.outHeight > 0 &&
        (mime == "image/jpeg" || mime == "image/png" || mime == "image/webp")
    ) {
        return Result.success(MomentMediaPayload(bytes, mime, extension))
    }

    val jpeg = try {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeForJpeg(bytes)
        } else {
            decodeForLegacyJpeg(bytes, bounds.outWidth, bounds.outHeight)
        }
        bitmap?.let {
            try {
                val output = ByteArrayOutputStream()
                if (it.compress(Bitmap.CompressFormat.JPEG, 92, output) && output.size() > 0) {
                    output.toByteArray()
                } else {
                    null
                }
            } finally {
                it.recycle()
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    return if (jpeg != null) {
        Result.success(MomentMediaPayload(jpeg, "image/jpeg", "jpg"))
    } else {
        Result.failure(IllegalArgumentException("No se pudo preparar la imagen. Usa JPG/PNG/WebP."))
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeForJpeg(bytes: ByteArray): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val size = info.size
        val edge = maxOf(size.width, size.height)
        if (edge > MAX_CONVERTED_IMAGE_EDGE) {
            val ratio = MAX_CONVERTED_IMAGE_EDGE.toDouble() / edge
            decoder.setTargetSize(
                maxOf(1, (size.width * ratio).toInt()),
                maxOf(1, (size.height * ratio).toInt()),
            )
        }
    }

private fun decodeForLegacyJpeg(bytes: ByteArray, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    var sample = 1
    val edge = maxOf(width, height).toLong()
    while ((edge + sample - 1) / sample > MAX_CONVERTED_IMAGE_EDGE) sample *= 2
    val orientation = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val matrix = exifOrientationMatrix(orientation)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
    }) ?: return null
    if (matrix.isIdentity) return bitmap

    var oriented: Bitmap? = null
    try {
        oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return oriented
    } finally {
        if (oriented !== bitmap) bitmap.recycle()
    }
}
