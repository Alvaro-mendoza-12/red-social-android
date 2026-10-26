package conectate.com.presentation.messages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import conectate.com.data.model.StickerItem

private val pickerEmojis = listOf(
    "😀" to "sonrisa feliz", "😃" to "alegre", "😄" to "risa feliz", "😁" to "sonriendo",
    "😆" to "riendo", "😅" to "risa nerviosa", "😂" to "lágrimas de risa", "🤣" to "carcajada",
    "😊" to "sonrisa amable", "🥰" to "cariño amor", "😍" to "enamorado", "😘" to "beso",
    "😎" to "gafas genial", "🤔" to "pensando", "🥹" to "emoción", "😢" to "triste",
    "😭" to "llorar", "😡" to "enojado", "😴" to "dormir", "🙃" to "al revés",
    "😉" to "guiño", "😮" to "sorpresa", "🥳" to "fiesta", "🤩" to "estrellas",
    "👍" to "me gusta pulgar arriba", "👎" to "pulgar abajo", "👏" to "aplausos", "🙌" to "celebrar",
    "🙏" to "gracias por favor", "👋" to "hola adiós", "✌️" to "paz", "🤝" to "acuerdo amigos",
    "💪" to "fuerza", "🫶" to "manos corazón", "🤞" to "suerte", "👌" to "perfecto",
    "❤️" to "amor corazón rojo", "💜" to "corazón morado", "💙" to "corazón azul", "💚" to "corazón verde",
    "💛" to "corazón amarillo", "🖤" to "corazón negro", "🤍" to "corazón blanco", "💕" to "corazones",
    "🔥" to "fuego", "✨" to "brillos", "🎉" to "celebración", "🎵" to "música",
    "🎮" to "juegos", "⚽" to "fútbol", "🏆" to "trofeo", "🌸" to "flor",
    "🌞" to "sol", "🌙" to "luna noche", "⭐" to "estrella", "☕" to "café",
    "🍕" to "pizza", "🐱" to "gato", "🐶" to "perro", "🌈" to "arcoíris"
)

internal fun searchablePickerEmojis(query: String): List<Pair<String, String>> {
    fun normalize(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
    val search = normalize(query.trim())
    return if (search.isEmpty()) pickerEmojis else pickerEmojis.filter { (emoji, description) ->
        emoji.contains(query.trim()) || normalize(description).contains(search)
    }
}

@Composable
fun StickerPickerDialog(
    onDismiss: () -> Unit,
    onStickerSelected: (StickerItem) -> Unit,
    viewModel: StickerPickerViewModel = hiltViewModel(),
    onEmojiSelected: ((String) -> Unit)? = null
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val favorites by viewModel.favoriteItems.collectAsState()
    val packs by viewModel.packs.collectAsState()
    val downloadedPacks by viewModel.downloadedPacks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()

    // Comments can use offline emojis immediately; other callers open the catalog
    // rather than a Favorites tab that may be empty on first use.
    var selectedTab by rememberSaveable { mutableStateOf(if (onEmojiSelected != null) 3 else 2) }
    var newPackName by rememberSaveable { mutableStateOf("") }
    var selectedPackId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastStickerTab by rememberSaveable { mutableStateOf(2) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val sectionStates = rememberSaveableStateHolder()
    val imagePickerLauncher = conectate.com.presentation.components.rememberInAppMediaPicker() { uri ->
        uri?.let { viewModel.createStickerFromImage(it, selectedPackId ?: packs.firstOrNull()?.id) }
    }

    LaunchedEffect(packs) {
        if (selectedPackId == null && packs.isNotEmpty()) {
            selectedPackId = packs.first().id
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    if (onEmojiSelected != null) {
                        IconToggleButton(checked = selectedTab == 3, onCheckedChange = { selectedTab = 3 }) {
                            Icon(Icons.Default.EmojiEmotions, "Emojis")
                        }
                        IconToggleButton(checked = selectedTab != 3, onCheckedChange = { selectedTab = lastStickerTab }) {
                            Icon(Icons.Default.StickyNote2, "Stickers")
                        }
                    } else Text("Stickers", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        searchExpanded = !searchExpanded
                        if (!searchExpanded) viewModel.updateQuery("")
                    }) { Icon(Icons.Default.Search, if (selectedTab == 3) "Buscar emojis" else "Buscar stickers") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cerrar selector") }
                }

                if (searchExpanded) OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.updateQuery(it) },
                    placeholder = { Text(if (selectedTab == 3) "Buscar emoji..." else "Buscar sticker...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { viewModel.updateQuery("") }) {
                            Icon(Icons.Default.Close, "Borrar búsqueda")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                if (selectedTab == 1) {
                    PackManagementSection(
                        newPackName = newPackName,
                        onNewPackNameChange = { newPackName = it },
                        onCreatePack = {
                            if (newPackName.isNotBlank()) {
                                viewModel.createPack(newPackName)
                                newPackName = ""
                            }
                        },
                        onAddImage = { imagePickerLauncher.launch("image/*") },
                        packs = packs,
                        selectedPackId = selectedPackId,
                        onPackSelect = { selectedPackId = it },
                        downloadedPacks = downloadedPacks,
                        onDownload = { viewModel.downloadPack(it) }
                    )
                }

                if (!actionMessage.isNullOrBlank()) {
                    Text(
                        text = actionMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    LaunchedEffect(actionMessage) {
                        viewModel.clearActionMessage()
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    val currentItems = when (selectedTab) {
                        0 -> favorites
                        1 -> {
                            val packIds = packs.map { it.id }.toSet()
                            val activePackId = selectedPackId
                            results.filter { item ->
                                item.pack_id != null &&
                                    packIds.contains(item.pack_id) &&
                                    (activePackId == null || item.pack_id == activePackId)
                            }
                        }
                        else -> results
                    }.filter { !it.media_url.isNullOrBlank() }

                    sectionStates.SaveableStateProvider("picker-$selectedTab-${if (selectedTab == 1) selectedPackId else ""}") {
                    if (selectedTab == 3 && onEmojiSelected != null) {
                        conectate.com.presentation.messages.stickers.ChatEmojiGrid(
                            query = query,
                            modifier = Modifier.fillMaxSize(),
                            onEmojiSelected = onEmojiSelected,
                        )
                    } else if (currentItems.isEmpty()) {
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) else Text(
                            "No hay stickers para mostrar",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(80.dp),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(currentItems, key = { it.id }, contentType = { "sticker" }) { sticker ->
                                StickerItemCard(
                                    sticker = sticker,
                                    isFavorite = viewModel.isFavorite(sticker),
                                    onSelect = { onStickerSelected(sticker) },
                                    onToggleFavorite = { viewModel.toggleFavorite(sticker.id) },
                                    onDelete = {
                                        if (sticker.source.equals("custom", ignoreCase = true)) {
                                            viewModel.deleteSticker(sticker.id)
                                        } else {
                                            viewModel.removeStickerFromPack(sticker, selectedPackId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    }
                    if (isLoading && currentItems.isNotEmpty() && selectedTab != 3) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                }

                if (selectedTab != 3) Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf(0 to Icons.Default.Star, 1 to Icons.Default.Folder, 2 to Icons.Default.Explore).forEach { (tab, icon) ->
                        IconToggleButton(checked = selectedTab == tab, onCheckedChange = { selectedTab = tab; lastStickerTab = tab }) {
                            Icon(icon, listOf("Favoritos", "Packs", "Explorar")[tab],
                                tint = if (selectedTab == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PackManagementSection(
    newPackName: String,
    onNewPackNameChange: (String) -> Unit,
    onCreatePack: () -> Unit,
    onAddImage: () -> Unit,
    packs: List<conectate.com.data.model.StickerPack>,
    selectedPackId: String?,
    onPackSelect: (String) -> Unit,
    downloadedPacks: Set<String>,
    onDownload: (String) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newPackName,
                onValueChange = onNewPackNameChange,
                placeholder = { Text("Nuevo pack") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            )
            IconButton(onClick = onCreatePack) {
                Icon(Icons.Default.Save, contentDescription = "Crear pack")
            }
        }
        TextButton(onClick = onAddImage, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
            Spacer(modifier = Modifier.size(6.dp))
            Text("Crear propio")
        }
        LazyColumn(modifier = Modifier.height(80.dp)) {
            items(packs) { pack ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPackSelect(pack.id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { onPackSelect(pack.id) },
                        label = { Text(pack.name) },
                        border = if (selectedPackId == pack.id) 
                            AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = MaterialTheme.colorScheme.primary,
                                borderWidth = 2.dp,
                            )
                            else AssistChipDefaults.assistChipBorder(enabled = true)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (!downloadedPacks.contains(pack.id)) {
                        IconButton(onClick = { onDownload(pack.id) }) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerItemCard(
    sticker: StickerItem,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(sticker.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onSelect,
                onLongClickLabel = "Opciones de sticker",
                onLongClick = { menuExpanded = true }
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!sticker.media_url.isNullOrBlank()) {
                conectate.com.presentation.components.StickerArtwork(
                    url = sticker.media_url,
                    thumbnailUrl = sticker.thumbnail_url,
                    description = sticker.title,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(sticker.title.take(1), style = MaterialTheme.typography.headlineMedium)
                }
            }
            
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavorite) "Quitar de favoritos" else "Añadir a favoritos",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Enviar sticker") }, onClick = {
                    menuExpanded = false
                    onSelect()
                })
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "Quitar de favoritos" else "Añadir a favoritos") },
                    onClick = {
                        menuExpanded = false
                        onToggleFavorite()
                    }
                )
            }
        }
    }
}

