package conectate.com.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

enum class ImageCropPreset(val label: String, val aspectRatio: Float?) {
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    PORTRAIT("4:5", 4f / 5f),
    WIDE("16:9", 16f / 9f),
    COVER("Portada", 3f)
}

/**
 * Recorte central predecible para imágenes elegidas desde cualquier pantalla.
 * El archivo resultante vive en la caché compartida por FileProvider y nunca
 * modifica ni sobrescribe el original elegido por el usuario.
 */
@Composable
fun SimpleImageCropDialog(
    sourceUri: Uri,
    title: String = "Recortar imagen",
    presets: List<ImageCropPreset> = listOf(
        ImageCropPreset.ORIGINAL,
        ImageCropPreset.SQUARE,
        ImageCropPreset.PORTRAIT,
        ImageCropPreset.WIDE
    ),
    initialPreset: ImageCropPreset = presets.firstOrNull() ?: ImageCropPreset.ORIGINAL,
    onDismiss: () -> Unit,
    onCropped: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPreset by remember(sourceUri) { mutableStateOf(initialPreset) }
    var isSaving by remember(sourceUri) { mutableStateOf(false) }
    var errorMessage by remember(sourceUri) { mutableStateOf<String?>(null) }
    val previewAspect = selectedPreset.aspectRatio ?: 1f

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) {
                        Text("Cancelar")
                    }
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Button(
                        enabled = !isSaving,
                        onClick = {
                            scope.launch {
                                isSaving = true
                                errorMessage = null
                                runCatching {
                                    cropImageToCache(context, sourceUri, selectedPreset.aspectRatio)
                                }.onSuccess(onCropped).onFailure {
                                    errorMessage = "No se pudo recortar la imagen. Inténtalo otra vez."
                                    isSaving = false
                                }
                            }
                        }
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Usar")
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = sourceUri,
                        contentDescription = "Vista previa del recorte",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(previewAspect)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = if (selectedPreset == ImageCropPreset.ORIGINAL) {
                            ContentScale.Fit
                        } else {
                            ContentScale.Crop
                        }
                    )
                    if (isSaving) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                if (presets.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        presets.distinct().forEach { preset ->
                            AssistChip(
                                onClick = { selectedPreset = preset },
                                enabled = !isSaving,
                                label = { Text(preset.label) },
                                leadingIcon = if (selectedPreset == preset) {
                                    { Text("✓") }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
                Text(
                    text = if (selectedPreset == ImageCropPreset.ORIGINAL) {
                        "Se conservará la imagen original."
                    } else {
                        "La imagen se ajustará al marco ${selectedPreset.label}."
                    },
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

private suspend fun cropImageToCache(
    context: Context,
    sourceUri: Uri,
    targetAspect: Float?
): Uri = withContext(Dispatchers.IO) {
    if (targetAspect == null) return@withContext sourceUri

    val decoded = decodeScaledBitmap(context, sourceUri)
    val cropped = centerCropBitmap(decoded, targetAspect)
    val output = File(context.cacheDir, "crop_${UUID.randomUUID()}.jpg")
    FileOutputStream(output).use { stream ->
        check(cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
            "No se pudo comprimir el recorte"
        }
    }
    if (cropped !== decoded) decoded.recycle()
    cropped.recycle()
    FileProvider.getUriForFile(context, "${context.packageName}.provider", output)
}

private fun decodeScaledBitmap(context: Context, uri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > 2_048) {
                decoder.setTargetSampleSize((longest / 2_048f).roundToInt().coerceAtLeast(1))
            }
        }
    }

    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri).use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
    var sampleSize = 1
    while (max(options.outWidth, options.outHeight) / sampleSize > 2_048) sampleSize *= 2
    val bitmap = context.contentResolver.openInputStream(uri).use { stream ->
        BitmapFactory.decodeStream(
            stream,
            null,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    } ?: error("No se pudo leer la imagen")
    val rotation = context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        runCatching {
            when (
                ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
    } ?: 0f
    if (rotation == 0f) return bitmap
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply {
        postRotate(rotation)
    }, true)
    bitmap.recycle()
    return rotated
}

private fun centerCropBitmap(source: Bitmap, targetAspect: Float): Bitmap {
    val sourceAspect = source.width.toFloat() / source.height.toFloat()
    val (cropWidth, cropHeight) = if (sourceAspect > targetAspect) {
        (source.height * targetAspect).roundToInt().coerceAtMost(source.width) to source.height
    } else {
        source.width to (source.width / targetAspect).roundToInt().coerceAtMost(source.height)
    }
    val left = ((source.width - cropWidth) / 2).coerceAtLeast(0)
    val top = ((source.height - cropHeight) / 2).coerceAtLeast(0)
    return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
}
