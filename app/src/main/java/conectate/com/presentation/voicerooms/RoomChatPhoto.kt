package conectate.com.presentation.voicerooms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RoomChatPhoto {
    const val PREFIX = "data:image/jpeg;base64,"
    fun bytes(value: String?): ByteArray? = runCatching {
        if (value == null || value.length > 132000 || !value.startsWith(PREFIX)) return@runCatching null
        val raw = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..720 || bounds.outHeight !in 1..720) return@runCatching null
        raw
    }.getOrNull()

    suspend fun prepare(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 12 * 1024 * 1024) { "Elige una foto de hasta 12 MB." }; output.write(buffer, 0, n) }
            output.toByteArray()
        } ?: error("No se pudo leer la foto.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw,0,raw.size,bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "El archivo no es una foto compatible." }
        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth,bounds.outHeight) / inSampleSize > 1440) inSampleSize *= 2 }
        val original = BitmapFactory.decodeByteArray(raw,0,raw.size,options) ?: error("No se pudo preparar la foto.")
        var rotated = original
        try {
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val matrix = conectate.com.core.media.exifOrientationMatrix(orientation)
            if (!matrix.isIdentity) rotated = Bitmap.createBitmap(original,0,0,original.width,original.height,matrix,true)
            val scale = minOf(1f,720f / maxOf(rotated.width,rotated.height))
            val result = Bitmap.createBitmap(maxOf(1,(rotated.width*scale).toInt()),maxOf(1,(rotated.height*scale).toInt()),Bitmap.Config.ARGB_8888)
            try {
                Canvas(result).apply { drawColor(Color.WHITE); drawBitmap(rotated,null,android.graphics.Rect(0,0,result.width,result.height),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
                for (quality in listOf(80,65,50,35,20)) {
                    val output = ByteArrayOutputStream()
                    check(result.compress(Bitmap.CompressFormat.JPEG,quality,output) && output.size() > 0) { "No se pudo convertir la foto. Elige otra imagen." }
                    val data = PREFIX + Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)
                    if (data.length <= 132000) return@withContext data
                }
                error("La foto tiene demasiado detalle. Elige una imagen más pequeña.")
            } finally { result.recycle() }
        } finally { if (rotated !== original) rotated.recycle(); original.recycle() }
    }
}
