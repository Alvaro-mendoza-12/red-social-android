package conectate.com.presentation.social

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.presentation.messages.MessageMediaComposer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePostScreen(
    navController: NavController,
    viewModel: CreatePostViewModel = hiltViewModel()
) {
    var caption by remember { mutableStateOf("") }
    var remixAllowed by remember { mutableStateOf(false) }
    var remixAudience by remember { mutableStateOf("public") }
    var isSensitive by remember { mutableStateOf(false) }
    val selectedMediaUris = remember { mutableStateListOf<Uri>() }
    var showMediaComposer by remember { mutableStateOf(false) }
    val isCreating by viewModel.isCreating.collectAsState()
    val error by viewModel.error.collectAsState()

    if (showMediaComposer) {
        MessageMediaComposer(
            maxItems = (7 - selectedMediaUris.size).coerceAtLeast(1),
            title = "Preparar publicación",
            confirmContentDescription = "Añadir a la publicación",
            isSending = false,
            onDismiss = { showMediaComposer = false },
            onSend = { media, preparedCaption ->
                selectedMediaUris.addAll(
                    media.map { it.uri }
                        .take((7 - selectedMediaUris.size).coerceAtLeast(0))
                )
                if (caption.isBlank() && preparedCaption.isNotBlank()) {
                    caption = preparedCaption
                }
                showMediaComposer = false
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.uploadSuccess.collect {
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nueva Publicación") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.createPost(
                                caption = caption,
                                mediaUris = selectedMediaUris,
                                remixAllowed = remixAllowed,
                                remixAudience = remixAudience,
                                isSensitive = isSensitive
                            )
                        },
                        enabled = !isCreating && (caption.isNotBlank() || selectedMediaUris.isNotEmpty())
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Publicar", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            TextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = { Text("¿Qué estás pensando?") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            if (selectedMediaUris.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedMediaUris) { uri ->
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { selectedMediaUris.remove(uri) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Quitar",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Permitir remixes", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Siempre se mostrará la publicación y el autor originales.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = remixAllowed,
                            onCheckedChange = { remixAllowed = it }
                        )
                    }
                    if (remixAllowed) {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = remixAudience == "public",
                                onClick = { remixAudience = "public" },
                                label = { Text("Todos") }
                            )
                            FilterChip(
                                selected = remixAudience == "followers",
                                onClick = { remixAudience = "followers" },
                                label = { Text("Solo seguidores") }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Contenido sensible (+18)", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Añade una cubierta según las preferencias de cada persona.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = isSensitive, onCheckedChange = { isSensitive = it })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    enabled = selectedMediaUris.size < 7,
                    onClick = {
                        showMediaComposer = true
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Agregar Multimedia")
                }
                Text("Agregar fotos o videos", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
