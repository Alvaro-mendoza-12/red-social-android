package conectate.com.presentation.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import conectate.com.R
import conectate.com.presentation.social.GalleryMedia
import conectate.com.presentation.social.MediaType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun UnifiedMediaPickerScreen(
    galleryMedia: List<GalleryMedia>,
    isMultipleSelection: Boolean = false,
    maxSelection: Int = 5,
    onMediaSelected: (List<GalleryMedia>) -> Unit,
    onCancel: () -> Unit,
    onCameraClick: () -> Unit,
    onLoadMedia: (android.content.Context) -> Unit
) {
    val context = LocalContext.current
    var selectedItems by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }

    val permissionsToRequest = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                android.Manifest.permission.CAMERA
            )
        }
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.CAMERA
            )
        }
        else -> {
            listOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.CAMERA
            )
        }
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissionsToRequest)

    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted || permissionsState.permissions.any { it.status.isGranted }) {
            onLoadMedia(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isMultipleSelection) "Seleccionar medios (${selectedItems.size}/$maxSelection)" else "Seleccionar medio") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, "Cerrar")
                    }
                },
                actions = {
                    if (isMultipleSelection && selectedItems.isNotEmpty()) {
                        TextButton(onClick = { onMediaSelected(selectedItems) }) {
                            Text("Aceptar", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (galleryMedia.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        // Camera Button
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { onCameraClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.CameraAlt, "Cámara", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text("Cámara", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }

                    items(galleryMedia.size) { index ->
                        val media = galleryMedia[index]
                        val isSelected = selectedItems.contains(media)
                        val selectionIndex = selectedItems.indexOf(media) + 1

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clickable {
                                    if (isMultipleSelection) {
                                        if (isSelected) {
                                            selectedItems = selectedItems - media
                                        } else if (selectedItems.size < maxSelection) {
                                            selectedItems = selectedItems + media
                                        } else {
                                            android.widget.Toast.makeText(context, "Máximo $maxSelection seleccionados", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        onMediaSelected(listOf(media))
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = media.uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (media.type == MediaType.VIDEO) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PlayCircle, null, tint = Color.White)
                                }
                                Text(
                                    text = formatDuration(media.duration),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.4f))
                                )
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                )
                                if (isMultipleSelection) {
                                    Text(
                                        text = selectionIndex.toString(),
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(4.dp)
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

@Composable
fun UnifiedMediaPicker(
    onDismiss: () -> Unit,
    onMediaSelected: (List<Pair<Uri, String>>) -> Unit
) {
    val context = LocalContext.current
    val viewModel: conectate.com.presentation.components.MediaPickerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val mediaItems by viewModel.galleryMedia.collectAsState()

    UnifiedMediaPickerScreen(
        galleryMedia = mediaItems,
        isMultipleSelection = true,
        maxSelection = 10,
        onMediaSelected = { selected ->
            onMediaSelected(selected.map { Pair(it.uri, if (it.type == MediaType.VIDEO) "video" else "image") })
        },
        onCancel = onDismiss,
        onCameraClick = { /* No impl in wrapper yet */ },
        onLoadMedia = { viewModel.loadGalleryMedia(it) }
    )
}
