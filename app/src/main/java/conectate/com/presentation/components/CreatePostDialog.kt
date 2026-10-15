package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import conectate.com.data.local.PostDraftStore
import kotlinx.coroutines.launch

@Composable
fun CreatePostDialog(
    onDismiss: () -> Unit,
    onPostCreated: (String, List<Uri>, String, Boolean, Boolean, String?) -> Unit,
    initialText: String? = null,
    initialUri: Uri? = null,
    ownerId: String? = null,
    mentionCandidates: List<conectate.com.data.model.User> = emptyList(),
    onMentionQueryChanged: (String, Int) -> Unit = { _, _ -> },
) {
    var contentDraft by remember(ownerId, initialText) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initialText.orEmpty()))
    }
    var content by remember(ownerId, initialText) { mutableStateOf(initialText.orEmpty()) }
    LaunchedEffect(content) {
        if (contentDraft.text != content) contentDraft = androidx.compose.ui.text.input.TextFieldValue(content, androidx.compose.ui.text.TextRange(content.length))
    }
    val activeMention = conectate.com.data.model.activeMentionAt(contentDraft.text, contentDraft.selection.end)
    val mentionSuggestions = activeMention?.let {
        conectate.com.data.model.roomMentionCandidates(mentionCandidates, it.query, ownerId)
    }.orEmpty()
    LaunchedEffect(contentDraft, ownerId) {
        onMentionQueryChanged(contentDraft.text, contentDraft.selection.end)
    }
    DisposableEffect(ownerId) { onDispose { onMentionQueryChanged("", 0) } }
    var selectedImageUris by remember(ownerId, initialUri) {
        mutableStateOf<List<Uri>>(if (initialUri != null) listOf(initialUri) else emptyList())
    }
    var pendingCropUris by remember(ownerId) { mutableStateOf<List<Uri>>(emptyList()) }
    var isPublic by remember(ownerId) { mutableStateOf(true) }
    var isSensitive by remember(ownerId) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var draftExists by remember(ownerId) { mutableStateOf(false) }
    var draftBusy by remember(ownerId) { mutableStateOf(false) }
    var draftMessage by remember(ownerId) { mutableStateOf<String?>(null) }
    var savedSnapshot by remember(ownerId) { mutableStateOf("") }
    var showDismissPrompt by remember(ownerId) { mutableStateOf(false) }
    var showPreview by remember(ownerId) { mutableStateOf(false) }
    val latestOwnerId by rememberUpdatedState(ownerId)

    fun snapshot(): String = listOf(
        content,
        selectedImageUris.joinToString("|") { it.toString() },
        isPublic.toString(),
        isSensitive.toString(),
    ).joinToString("\u0000")

    LaunchedEffect(ownerId, initialText, initialUri) {
        if (ownerId.isNullOrBlank() || initialText != null || initialUri != null) return@LaunchedEffect
        draftBusy = true
        PostDraftStore.load(context, ownerId).fold(
            onSuccess = { draft ->
                if (draft != null) {
                    content = draft.content
                    selectedImageUris = draft.mediaUris
                    isPublic = draft.visibility != "private"
                    isSensitive = draft.isSensitive
                    draftExists = true
                    savedSnapshot = snapshot()
                    draftMessage = "Borrador restaurado"
                }
            },
            onFailure = {
                draftExists = true
                draftMessage = it.message ?: "No se pudo restaurar el borrador"
            },
        )
        draftBusy = false
    }

    fun saveDraft(closeAfter: Boolean = false) {
        val account = ownerId?.takeIf { it.isNotBlank() } ?: return
        if (draftBusy || pendingCropUris.isNotEmpty()) return
        draftBusy = true
        scope.launch {
            val draftContent = content
            val draftUris = selectedImageUris
            val draftVisibility = if (isPublic) "public" else "private"
            val draftSensitive = isSensitive
            PostDraftStore.save(
                context, account, draftContent, draftUris,
                draftVisibility, draftSensitive,
            ).fold(
                onSuccess = { draft ->
                    if (latestOwnerId != account) return@fold
                    selectedImageUris = draft.mediaUris
                    draftExists = true
                    savedSnapshot = snapshot()
                    draftMessage = "Borrador guardado en este dispositivo"
                    if (closeAfter) onDismiss()
                },
                onFailure = { draftMessage = it.message ?: "No se pudo guardar el borrador" },
            )
            draftBusy = false
        }
    }

    fun publish() {
        if (draftBusy || pendingCropUris.isNotEmpty() ||
            (content.isBlank() && selectedImageUris.isEmpty())) return
        val account = ownerId?.takeIf { it.isNotBlank() }
        val postContent = content
        val postUris = selectedImageUris
        val postVisibility = if (isPublic) "public" else "private"
        val postSensitive = isSensitive
        if (account == null) {
            onPostCreated(postContent, postUris, postVisibility, postSensitive, false, null)
            return
        }
        draftBusy = true
        scope.launch {
            PostDraftStore.save(context, account, postContent, postUris, postVisibility, postSensitive).fold(
                onSuccess = { draft ->
                    if (latestOwnerId != account) return@fold
                    draftExists = true
                    selectedImageUris = draft.mediaUris
                    savedSnapshot = snapshot()
                    onPostCreated(postContent, draft.mediaUris, postVisibility, postSensitive, true, account)
                },
                onFailure = { draftMessage = it.message ?: "No se pudo preparar la publicación" },
            )
            draftBusy = false
        }
    }

    fun requestDismiss() {
        val changed = snapshot() != savedSnapshot
        if (changed && (content.isNotBlank() || selectedImageUris.isNotEmpty() || draftExists)) {
            showDismissPrompt = true
        } else {
            onDismiss()
        }
    }
    
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
        onDismissRequest = ::requestDismiss,
        title = { Text("Crear Publicación") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                if (draftMessage != null) {
                    Text(draftMessage.orEmpty(), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = contentDraft,
                    visualTransformation = mentionInputTransformation,
                    onValueChange = { contentDraft = it; content = it.text },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("¿Qué estás pensando?") },
                    label = { Text("Contenido") }
                )
                MentionSuggestions(mentionSuggestions, onSelected = { user ->
                    conectate.com.data.model.activeMentionAt(contentDraft.text, contentDraft.selection.end)?.let { active ->
                        val (text, cursor) = conectate.com.data.model.insertMention(contentDraft.text, active, conectate.com.data.model.profilePublicHandle(user))
                        contentDraft = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(cursor))
                        content = text
                    }
                })
                
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

                Text("Audiencia", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isPublic, onClick = { isPublic = true }, label = { Text("Público") })
                    FilterChip(selected = !isPublic, onClick = { isPublic = false }, label = { Text("Privado") })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Contenido sensible (+18)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Se mostrará cubierto según la preferencia del usuario.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isSensitive, onCheckedChange = { isSensitive = it })
                }
                OutlinedButton(
                    onClick = { showPreview = true },
                    enabled = content.isNotBlank() || selectedImageUris.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Vista previa para ${if (isPublic) "todos" else "solo ti"}") }
                if (!ownerId.isNullOrBlank()) {
                    TextButton(
                        onClick = { saveDraft() },
                        enabled = !draftBusy && pendingCropUris.isEmpty(),
                    ) { Text(if (draftBusy) "Guardando…" else "Guardar borrador") }
                    if (draftExists) {
                        TextButton(
                            onClick = {
                                draftBusy = true
                                scope.launch {
                                    PostDraftStore.delete(context, ownerId).fold(
                                        onSuccess = {
                                            draftExists = false
                                            savedSnapshot = ""
                                            content = ""
                                            selectedImageUris = emptyList()
                                            isPublic = true
                                            isSensitive = false
                                            draftMessage = "Borrador eliminado"
                                        },
                                        onFailure = { draftMessage = it.message ?: "No se pudo eliminar" },
                                    )
                                    draftBusy = false
                                }
                            },
                            enabled = !draftBusy,
                        ) { Text("Eliminar borrador") }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::publish,
                enabled = !draftBusy && pendingCropUris.isEmpty() &&
                    (content.isNotBlank() || selectedImageUris.isNotEmpty()),
            ) {
                Text(if (draftBusy) "Preparando…" else "Publicar")
            }
        },
        dismissButton = {
            TextButton(onClick = ::requestDismiss) {
                Text("Cancelar")
            }
        }
    )

    if (showPreview) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("Vista previa del contenido") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (isPublic) "Pública · cualquier persona podrá verla"
                        else "Privada · solo tú podrás verla",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    SensitiveContentGate(isSensitive = isSensitive) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (content.isNotBlank()) {
                                Text(content, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (selectedImageUris.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(selectedImageUris.size) { index ->
                                        AsyncImage(
                                            model = selectedImageUris[index],
                                            contentDescription = "Imagen ${index + 1} de ${selectedImageUris.size}",
                                            modifier = Modifier.width(220.dp).height(180.dp)
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPreview = false }) { Text("Volver a editar") }
            },
        )
    }

    if (showDismissPrompt) {
        AlertDialog(
            onDismissRequest = { showDismissPrompt = false },
            title = { Text("¿Guardar cambios?") },
            text = { Text("Puedes continuar esta publicación más tarde desde Social o tu perfil.") },
            confirmButton = {
                TextButton(
                    onClick = { showDismissPrompt = false; saveDraft(closeAfter = true) },
                    enabled = !ownerId.isNullOrBlank() && !draftBusy,
                ) { Text("Guardar y salir") }
            },
            dismissButton = {
                TextButton(onClick = { showDismissPrompt = false; onDismiss() }) {
                    Text("Descartar cambios")
                }
            },
        )
    }
}
