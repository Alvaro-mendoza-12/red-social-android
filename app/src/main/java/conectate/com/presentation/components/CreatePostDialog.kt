package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun CreatePostDialog(
    onDismiss: () -> Unit,
    onPostCreated: (String, List<Uri>, String) -> Unit,
    initialText: String? = null,
    initialUri: Uri? = null
) {
    var content by remember { mutableStateOf(initialText ?: "") }
    var selectedImageUris by remember { mutableStateOf<List<Uri>>(if (initialUri != null) listOf(initialUri) else emptyList()) }
    var pendingCropUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isPublic by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.size > 7) {
            android.widget.Toast.makeText(context, "Máximo 7 imágenes permitidas", android.widget.Toast.LENGTH_SHORT).show()
        }
        selectedImageUris = emptyList()
        pendingCropUris = uris.take(7)
    }

    pendingCropUris.firstOrNull()?.let { sourceUri ->
        SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Recortar foto ${selectedImageUris.size + 1} de ${selectedImageUris.size + pendingCropUris.size}",
            presets = listOf(
                ImageCropPreset.ORIGINAL,
                ImageCropPreset.SQUARE,
                ImageCropPreset.PORTRAIT,
                ImageCropPreset.WIDE
            ),
            initialPreset = ImageCropPreset.ORIGINAL,
            onDismiss = { pendingCropUris = pendingCropUris.drop(1) },
            onCropped = { croppedUri ->
                selectedImageUris = selectedImageUris + croppedUri
                pendingCropUris = pendingCropUris.drop(1)
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crear Publicación") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("¿Qué estás pensando?") },
                    label = { Text("Contenido") }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (selectedImageUris.isNotEmpty()) {
                    Text("Imágenes seleccionadas: ${selectedImageUris.size}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    ) {
                        items(selectedImageUris.size) { index ->
                            Box(modifier = Modifier.width(100.dp).fillMaxHeight()) {
                                AsyncImage(
                                    model = selectedImageUris[index],
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = { 
                                        selectedImageUris = selectedImageUris.toMutableList().also { it.removeAt(index) } 
                                    },
                                    modifier = Modifier.align(Alignment.TopEnd).background(androidx.compose.ui.graphics.Color.Black.copy(alpha=0.5f), androidx.compose.foundation.shape.CircleShape).size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Eliminar", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    
                    TextButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cambiar selección")
                    }
                } else {
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Agregar Fotos (Máx 7)")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Público", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = isPublic,
                        onCheckedChange = { isPublic = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPostCreated(content, selectedImageUris, if (isPublic) "public" else "private") }
            ) {
                Text("Publicar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
