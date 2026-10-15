package conectate.com.presentation.messages

import conectate.com.core.media.readMediaBytesWithLimit

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.AddAPhoto
import conectate.com.util.VideoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCommunityScreen(
    navController: NavController,
    viewModel: CreateCommunityViewModel = hiltViewModel()
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(true) }
    var showMediaPicker by remember { mutableStateOf(false) }

    val isCreating by viewModel.isCreating.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedImageBytes by viewModel.selectedImageBytes.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (showMediaPicker) {
        MessageMediaComposer(
            initialFilter = "all",
            maxItems = 1,
            allowedMediaTypes = setOf("image", "video"),
            title = "Elegir icono de la comunidad",
            confirmContentDescription = "Usar como icono",
            showViewOnceOption = false,
            showCaptionInput = false,
            isSending = false,
            onDismiss = { showMediaPicker = false },
            onSend = { selected, _ ->
                val media = selected.firstOrNull() ?: return@MessageMediaComposer
                showMediaPicker = false
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        if (media.type == "video") {
                            VideoUtils.generateVideoThumbnail(context, media.uri)
                        } else {
                            runCatching { context.contentResolver.openInputStream(media.uri)?.use { it.readMediaBytesWithLimit(60L * 1024 * 1024) } }.getOrNull()
                        }
                    }
                    viewModel.setImageBytes(bytes)
                    if (bytes == null) {
                        android.widget.Toast.makeText(
                            context,
                            "No se pudo preparar ese archivo. Elige otro.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nueva Comunidad") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Image Picker
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showMediaPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedImageBytes != null) {
                        AsyncImage(
                            model = selectedImageBytes,
                            contentDescription = "Imagen seleccionada",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AddAPhoto,
                            contentDescription = "Seleccionar foto",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre de la comunidad") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Descripción") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Categoría (ej: Gaming, Música, Tech)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Pública", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Cualquiera puede unirse y ver mensajes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = isPublic, onCheckedChange = { isPublic = it })
            }

            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    viewModel.createCommunity(name, description, category, isPublic) { 
                        navController.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !isCreating && name.isNotBlank(),
                shape = MaterialTheme.shapes.medium
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Crear Comunidad")
                }
            }
        }
    }
}
