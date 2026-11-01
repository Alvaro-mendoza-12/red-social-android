package conectate.com.presentation.messages

import conectate.com.core.media.readMediaBytesWithLimit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val sessionCurrent by viewModel.sessionCurrent.collectAsStateWithLifecycle()
    if (!sessionCurrent || !viewModel.isSessionCurrent()) {
        CollectionCreationSessionNotice { navController.popBackStack() }
        return
    }
    val recovery by viewModel.recoveryState.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    if (recovery.showRecovery) {
        CollectionCreationRecoveryScreen(recovery.pending?.name, recovery.loading || isCreating, error,
            onContinue = {
                if (recovery.pending == null) viewModel.retryRecovery()
                else viewModel.retryPending { navController.openCreatedCollection("community", it) }
            }, onBack = { navController.popBackStack() })
        return
    }
    key(viewModel.ownerAccountId) { CreateCommunityForm(navController, viewModel) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCommunityForm(navController: NavController, viewModel: CreateCommunityViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var isPublic by rememberSaveable { mutableStateOf(true) }
    var showMediaPicker by remember { mutableStateOf(false) }

    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    val isPreparingImage by viewModel.isPreparingImage.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val selectedImageBytes by viewModel.selectedImageBytes.collectAsStateWithLifecycle()

    val context = LocalContext.current

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
                viewModel.prepareImage {
                    withContext(Dispatchers.IO) {
                        val raw = if (media.type == "video") VideoUtils.generateVideoThumbnail(context.applicationContext, media.uri)
                        else context.contentResolver.openInputStream(media.uri)?.use {
                            it.readMediaBytesWithLimit(60L * 1024 * 1024)
                        }
                        raw?.let { conectate.com.core.media.normalizeCollectionIcon(it).bytes }
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
            if (isPreparingImage) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Preparando icono…", style = MaterialTheme.typography.bodySmall)
            }
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
                        .clickable(enabled = !isCreating && !isPreparingImage) { showMediaPicker = true },
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
                enabled = !isCreating,
                onValueChange = { name = it },
                label = { Text("Nombre de la comunidad") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = description,
                enabled = !isCreating,
                onValueChange = { description = it },
                label = { Text("Descripción") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            OutlinedTextField(
                value = category,
                enabled = !isCreating,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Pública", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Cualquiera puede unirse y ver mensajes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(enabled = !isCreating, checked = isPublic, onCheckedChange = { isPublic = it })
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
                    viewModel.createCommunity(name, description, category, isPublic) { id ->
                        navController.openCreatedCollection("community", id)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !isCreating && !isPreparingImage && name.isNotBlank(),
                shape = MaterialTheme.shapes.medium
            ) {
                if (isCreating || isPreparingImage) {
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
