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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
    FREE("Libre", null),
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    PORTRAIT("4:5", 4f / 5f),
    WIDE("16:9", 16f / 9f),
    COVER("Portada", 3f)
}

private data class NormalizedImageCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
)

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
    var sourceAspect by remember(sourceUri) { mutableStateOf(1f) }
    var crop by remember(sourceUri) { mutableStateOf(NormalizedImageCrop()) }
    var isSaving by remember(sourceUri) { mutableStateOf(false) }
    var errorMessage by remember(sourceUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(sourceUri) {
        sourceAspect = readImageAspect(context, sourceUri)
        crop = cropForPreset(initialPreset, sourceAspect)
    }

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
                                    cropImageToCache(context, sourceUri, crop)
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

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val containerAspect = if (maxHeight.value > 0f) {
                        maxWidth.value / maxHeight.value
                    } else {
                        sourceAspect
                    }
                    Box(
                        modifier = if (sourceAspect >= containerAspect) {
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(sourceAspect)
                        } else {
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(sourceAspect)
                        }.clip(RoundedCornerShape(12.dp))
                    ) {
                        AsyncImage(
                            model = sourceUri,
                            contentDescription = "Vista previa del recorte",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        ManualImageCropOverlay(
                            crop = crop,
                            onCropChange = { updated ->
                                crop = updated
                                selectedPreset = ImageCropPreset.FREE
                            }
                        )
                    }
                    if (isSaving) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                val availablePresets = remember(presets) {
                    (listOf(ImageCropPreset.FREE) + presets).distinct()
                }
                if (availablePresets.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availablePresets.forEach { preset ->
                            AssistChip(
                                onClick = {
                                    selectedPreset = preset
                                    crop = cropForPreset(preset, sourceAspect)
                                },
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
                    text = "Arrastra las esquinas o mueve el marco para elegir exactamente qué parte usar.",
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
    crop: NormalizedImageCrop
): Uri = withContext(Dispatchers.IO) {
    if (crop == NormalizedImageCrop()) return@withContext sourceUri

    val decoded = decodeScaledBitmap(context, sourceUri)
    val cropped = cropBitmap(decoded, crop)
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

private suspend fun readImageAspect(context: Context, uri: Uri): Float = withContext(Dispatchers.IO) {
    runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        val width = options.outWidth.coerceAtLeast(1)
        val height = options.outHeight.coerceAtLeast(1)
        width.toFloat() / height.toFloat()
    }.getOrDefault(1f)
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

private fun cropForPreset(preset: ImageCropPreset, sourceAspect: Float): NormalizedImageCrop {
    if (preset == ImageCropPreset.ORIGINAL) return NormalizedImageCrop()
    if (preset == ImageCropPreset.FREE || preset.aspectRatio == null) {
        return NormalizedImageCrop(0.08f, 0.08f, 0.92f, 0.92f)
    }
    val targetAspect = preset.aspectRatio
    return if (sourceAspect > targetAspect) {
        val width = (targetAspect / sourceAspect).coerceIn(0.05f, 1f)
        val left = (1f - width) / 2f
        NormalizedImageCrop(left, 0f, left + width, 1f)
    } else {
        val height = (sourceAspect / targetAspect).coerceIn(0.05f, 1f)
        val top = (1f - height) / 2f
        NormalizedImageCrop(0f, top, 1f, top + height)
    }
}

private fun cropBitmap(source: Bitmap, crop: NormalizedImageCrop): Bitmap {
    val left = (crop.left.coerceIn(0f, 1f) * source.width).roundToInt()
        .coerceIn(0, source.width - 1)
    val top = (crop.top.coerceIn(0f, 1f) * source.height).roundToInt()
        .coerceIn(0, source.height - 1)
    val right = (crop.right.coerceIn(0f, 1f) * source.width).roundToInt()
        .coerceIn(left + 1, source.width)
    val bottom = (crop.bottom.coerceIn(0f, 1f) * source.height).roundToInt()
        .coerceIn(top + 1, source.height)
    return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
}

@Composable
private fun ManualImageCropOverlay(
    crop: NormalizedImageCrop,
    onCropChange: (NormalizedImageCrop) -> Unit
) {
    val handleColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(crop) {
                val minimumSpan = 0.08f
                var dragTarget = -1
                detectDragGestures(
                    onDragStart = { point ->
                        val corners = listOf(
                            Offset(crop.left * size.width, crop.top * size.height),
                            Offset(crop.right * size.width, crop.top * size.height),
                            Offset(crop.left * size.width, crop.bottom * size.height),
                            Offset(crop.right * size.width, crop.bottom * size.height)
                        )
                        val nearest = corners.indices.minByOrNull { index ->
                            (corners[index] - point).getDistance()
                        } ?: -1
                        dragTarget = if (
                            nearest >= 0 &&
                            (corners[nearest] - point).getDistance() <= 52.dp.toPx()
                        ) {
                            nearest
                        } else {
                            val frame = Rect(
                                crop.left * size.width,
                                crop.top * size.height,
                                crop.right * size.width,
                                crop.bottom * size.height
                            )
                            if (frame.contains(point)) 4 else -1
                        }
                    },
                    onDragEnd = { dragTarget = -1 },
                    onDragCancel = { dragTarget = -1 }
                ) { change, dragAmount ->
                    change.consume()
                    val dx = dragAmount.x / size.width.coerceAtLeast(1).toFloat()
                    val dy = dragAmount.y / size.height.coerceAtLeast(1).toFloat()
                    val updated = when (dragTarget) {
                        0 -> crop.copy(
                            left = (crop.left + dx).coerceIn(0f, crop.right - minimumSpan),
                            top = (crop.top + dy).coerceIn(0f, crop.bottom - minimumSpan)
                        )
                        1 -> crop.copy(
                            right = (crop.right + dx).coerceIn(crop.left + minimumSpan, 1f),
                            top = (crop.top + dy).coerceIn(0f, crop.bottom - minimumSpan)
                        )
                        2 -> crop.copy(
                            left = (crop.left + dx).coerceIn(0f, crop.right - minimumSpan),
                            bottom = (crop.bottom + dy).coerceIn(crop.top + minimumSpan, 1f)
                        )
                        3 -> crop.copy(
                            right = (crop.right + dx).coerceIn(crop.left + minimumSpan, 1f),
                            bottom = (crop.bottom + dy).coerceIn(crop.top + minimumSpan, 1f)
                        )
                        4 -> {
                            val width = crop.right - crop.left
                            val height = crop.bottom - crop.top
                            val left = (crop.left + dx).coerceIn(0f, 1f - width)
                            val top = (crop.top + dy).coerceIn(0f, 1f - height)
                            NormalizedImageCrop(left, top, left + width, top + height)
                        }
                        else -> crop
                    }
                    if (updated != crop) onCropChange(updated)
                }
            }
    ) {
        val frame = Rect(
            crop.left * size.width,
            crop.top * size.height,
            crop.right * size.width,
            crop.bottom * size.height
        )
        val shade = Color.Black.copy(alpha = 0.58f)
        drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, frame.top))
        drawRect(
            shade,
            topLeft = Offset(0f, frame.bottom),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - frame.bottom)
        )
        drawRect(
            shade,
            topLeft = Offset(0f, frame.top),
            size = androidx.compose.ui.geometry.Size(frame.left, frame.height)
        )
        drawRect(
            shade,
            topLeft = Offset(frame.right, frame.top),
            size = androidx.compose.ui.geometry.Size(size.width - frame.right, frame.height)
        )
        drawRect(Color.White, topLeft = frame.topLeft, size = frame.size, style = Stroke(width = 3.dp.toPx()))
        val radius = 8.dp.toPx()
        listOf(frame.topLeft, frame.topRight, frame.bottomLeft, frame.bottomRight).forEach { corner ->
            drawCircle(Color.White, radius = radius, center = corner)
            drawCircle(handleColor, radius = radius * 0.55f, center = corner)
        }
    }
}
