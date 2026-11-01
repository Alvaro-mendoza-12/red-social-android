package conectate.com.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal data class CollectionIconContent(val bytes: ByteArray, val contentType: String, val extension: String)

/** Runs on IO. Icons use bounded pixels and a content type that matches the actual bytes. */
internal fun normalizeCollectionIcon(bytes: ByteArray): CollectionIconContent {
    require(bytes.isNotEmpty() && bytes.size <= 60 * 1024 * 1024) { "Elige una imagen de hasta 60 MB." }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "No pudimos leer esa imagen. Elige otra copia." }
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val extension = when (bounds.outMimeType) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> null }
    if (extension != null && maxOf(bounds.outWidth, bounds.outHeight) <= 1024 && orientation in 0..1)
        return CollectionIconContent(bytes, bounds.outMimeType, extension)

    var sample = 1
    while ((maxOf(bounds.outWidth, bounds.outHeight).toLong() + sample - 1) / sample > 1024) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("No pudimos preparar esa imagen. Elige otra copia.")
    var oriented = decoded
    try {
        val transparent = decoded.hasAlpha()
        val matrix = exifOrientationMatrix(orientation)
        if (!matrix.isIdentity) oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val output = ByteArrayOutputStream()
        check(oriented.compress(if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, output)) {
            "No pudimos convertir la imagen. Elige otra copia."
        }
        check(output.size() > 0) { "No pudimos preparar la imagen." }
        return CollectionIconContent(output.toByteArray(), if (transparent) "image/png" else "image/jpeg", if (transparent) "png" else "jpg")
    } finally {
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
    }
}
