package conectate.com.presentation.videos

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoUploadViewModel @Inject constructor(
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    private val _isPublic = MutableStateFlow(true)
    val isPublic: StateFlow<Boolean> = _isPublic.asStateFlow()

    private val _tags = MutableStateFlow("")
    val tags: StateFlow<String> = _tags.asStateFlow()

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    private val _thumbnailUri = MutableStateFlow<Uri?>(null)
    val thumbnailUri: StateFlow<Uri?> = _thumbnailUri.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

    fun updateTitle(value: String) { _title.value = value }
    fun updateDescription(value: String) { _description.value = value }
    fun updateIsPublic(value: Boolean) { _isPublic.value = value }
    fun updateTags(value: String) { _tags.value = value }

    fun setVideoUri(uri: Uri, context: android.content.Context) {
        if (checkVideoDuration(uri, context)) {
            _videoUri.value = uri
            _uploadError.value = null
        } else {
            _videoUri.value = null
            _uploadError.value = "El video no puede durar más de 30 segundos."
        }
    }
    
    fun setThumbnailUri(uri: Uri?) { _thumbnailUri.value = uri } // Allow nullable

    private fun checkVideoDuration(uri: Uri, context: android.content.Context): Boolean {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val time = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            val timeInMillisec = time?.toLong() ?: 0L
            // limit to 30 seconds (30000 ms) + small buffer (e.g. 1s)
            timeInMillisec <= 31000 
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun uploadVideo(onComplete: (Boolean) -> Unit) {
        val video = _videoUri.value
        val thumbnail = _thumbnailUri.value
        
        if (video == null || _title.value.isBlank()) { 
            _uploadError.value = "Por favor completa todos los campos (Video, Miniatura, Título)"
            return
        }

        viewModelScope.launch {
            _isUploading.value = true
            _uploadProgress.value = 0f
            _uploadError.value = null

            // Simulated progress for UX
            val progressJob = launch {
                while (_uploadProgress.value < 0.9f) {
                    kotlinx.coroutines.delay(500)
                    _uploadProgress.value += 0.1f
                }
            }

            try {
                // Parse tags
                val tagsList = _tags.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                val result = videoRepository.uploadVideo(
                    title = _title.value,
                    description = _description.value,
                    videoUri = video,
                    thumbnailUri = thumbnail,
                    isPublic = _isPublic.value,
                    tags = tagsList
                )
                
                progressJob.cancel()
                _uploadProgress.value = 1.0f

                result.fold(
                    onSuccess = {
                        _isUploading.value = false
                        onComplete(true)
                    },
                    onFailure = {
                        _isUploading.value = false
                        _uploadError.value = it.message
                        onComplete(false)
                    }
                )
            } catch (e: Exception) {
                progressJob.cancel()
                _isUploading.value = false
                _uploadError.value = e.message
                onComplete(false)
            }
        }
    }

    fun resetState() {
        _title.value = ""
        _description.value = ""
        _isPublic.value = true
        _tags.value = ""
        _videoUri.value = null
        _thumbnailUri.value = null
        _uploadError.value = null
        _uploadProgress.value = 0f
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoUploadScreen(
    navController: NavController,
    viewModel: VideoUploadViewModel = hiltViewModel()
) {
    val title by viewModel.title.collectAsState()
    val description by viewModel.description.collectAsState()
    val isPublic by viewModel.isPublic.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val videoUri by viewModel.videoUri.collectAsState()
    val thumbnailUri by viewModel.thumbnailUri.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val uploadError by viewModel.uploadError.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.setVideoUri(it, context) } }

    val thumbnailPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.setThumbnailUri(it) } }

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Handle result from Editor
    val editedVideoUri = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.get<String>("editedVideo")
    
    LaunchedEffect(editedVideoUri) {
        editedVideoUri?.let { uriString ->
            val uri = Uri.parse(uriString)
            viewModel.setVideoUri(uri, context)
            // Clear the handle so it doesn't trigger again
            navController.currentBackStackEntry?.savedStateHandle?.remove<String>("editedVideo")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subir Video") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Atrás")
                    }
                }
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (isUploading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp).fillMaxWidth()
                ) {
                    Text("Subiendo video...", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = uploadProgress,
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(uploadProgress * 100).toInt()}%")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Video Selector (Shows preview if selected)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.2f))
                        .clickable { videoPickerLauncher.launch("video/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (videoUri != null) {
                        // Display Video Preview using Coil
                        AsyncImage(
                            model = videoUri, // Coil can load video frames
                            contentDescription = "Vista previa del video",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Overlay play icon to indicate it's a video
                         Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                             Icon(
                                imageVector = Icons.Default.PlayCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    } else {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.VideoCall, null, modifier = Modifier.size(48.dp))
                            Text("Seleccionar Video (Obligatorio)")
                        }
                    }
                }

                if (videoUri != null) {
                    OutlinedButton(
                        onClick = {
                            navController.navigate(conectate.com.presentation.navigation.Screen.VideoEditor.createRoute(videoUri!!))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Editar Video (Opciones Pro)")
                    }
                }

                // Thumbnail Selector (Optional)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.2f))
                        .clickable { thumbnailPickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnailUri != null) {
                         AsyncImage(
                            model = thumbnailUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Remove button
                        IconButton(
                            onClick = { viewModel.setThumbnailUri(Uri.EMPTY) }, // Logic to clear? Or just let them replace
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        ) {
                             Icon(Icons.Default.Close, contentDescription = "Quitar", tint = Color.White)
                        }

                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(32.dp))
                            Text("Seleccionar Miniatura (Opcional)")
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { viewModel.updateTitle(it) },
                    label = { Text("Título") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { viewModel.updateDescription(it) },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                
                 OutlinedTextField(
                    value = tags,
                    onValueChange = { viewModel.updateTags(it) },
                    label = { Text("Hashtags (separados por coma)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ej: fyp, viral, musica") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Público")
                    Switch(
                        checked = isPublic,
                        onCheckedChange = { viewModel.updateIsPublic(it) }
                    )
                }

                if (uploadError != null) {
                    Text(uploadError!!, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = {
                        viewModel.uploadVideo { success ->
                            if (success) {
                                viewModel.resetState() 
                                // Use Toast for reliable feedback that persists across navigation
                                android.widget.Toast.makeText(context, "Video subido exitosamente!", android.widget.Toast.LENGTH_SHORT).show()
                                
                                // Navigate to Home with Videos tab selected
                                val targetRoute = conectate.com.presentation.navigation.Screen.Home.createRoute(
                                    startTab = conectate.com.presentation.navigation.Screen.Videos.route
                                )
                                
                                try {
                                    navController.navigate(targetRoute) {
                                        // Pop up to the root/login to ensure we clear the stack if needed
                                        // But mainly we want to clear the previous Home instance so it reloads with the new tab
                                        popUpTo(conectate.com.presentation.navigation.Screen.Home.route) { 
                                            inclusive = true 
                                        }
                                        launchSingleTop = true
                                    }
                                } catch (e: Exception) {
                                    // Fallback
                                    navController.navigate("home")
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // Validation: Only Video and Title required now
                    enabled = videoUri != null && title.isNotBlank()
                ) {
                    Text("Publicar Video")
                }
            }
        }
    }
}
