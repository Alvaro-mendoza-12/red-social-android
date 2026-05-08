package conectate.com.presentation.media

import androidx.compose.runtime.Composable

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenImageScreen(
    imageUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Atrás", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                             // Save Logic
                             saveImageToGallery(context, imageUrl) { success -> 
                                 scope.launch {
                                     snackbarHostState.showSnackbar(
                                         if(success) "Imagen guardada" else "Error al guardar"
                                     )
                                 }
                             }
                        }
                    }) {
                        Icon(Icons.Default.Download, "Guardar", tint = Color.White)
                    }
                    IconButton(onClick = {
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, imageUrl)
                        }
                        context.startActivity(android.content.Intent.createChooser(i, "Compartir Imagen"))
                    }) {
                        Icon(Icons.Default.Share, "Compartir", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Full Screen Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

suspend fun saveImageToGallery(context: android.content.Context, imageUrl: String, onResult: (Boolean) -> Unit) {
    withContext(Dispatchers.IO) {
        try {
            val loader = coil.ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build()
            val result = (loader.execute(request) as? coil.request.SuccessResult)?.drawable
            val bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap

            if (bitmap != null) {
                val filename = "IMG_${System.currentTimeMillis()}.jpg"
                var fos: java.io.OutputStream? = null
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    context.contentResolver?.also { resolver ->
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                        }
                        val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        fos = imageUri?.let { resolver.openOutputStream(it) }
                    }
                } else {
                    val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val image = java.io.File(imagesDir, filename)
                    fos = java.io.FileOutputStream(image)
                }

                fos?.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                    withContext(Dispatchers.Main) { onResult(true) }
                } ?: withContext(Dispatchers.Main) { onResult(false) }
            } else {
                 withContext(Dispatchers.Main) { onResult(false) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { onResult(false) }
        }
    }
}
