package conectate.com.presentation.messages.stickers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.hilt.navigation.compose.hiltViewModel
import conectate.com.data.model.StickerItem
import conectate.com.presentation.messages.StickerPickerViewModel
import conectate.com.presentation.messages.searchablePickerEmojis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    onStickerSelected: (StickerItem) -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
    viewModel: StickerPickerViewModel? = null,
    onEmojiSelected: ((String) -> Unit)? = null,
    onEmojiBackspace: (() -> Unit)? = null,
) {
    if (!visible) return
    val model = viewModel ?: hiltViewModel<StickerPickerViewModel>()

    val query by model.query.collectAsState()
    val results by model.searchResults.collectAsState()
    val favorites by model.favoriteItems.collectAsState()
    val recents by model.recentItems.collectAsState()
    val packs by model.packs.collectAsState()
    val isLoading by model.isLoading.collectAsState()
    val actionMessage by model.actionMessage.collectAsState()

    var selectedTab by remember { mutableStateOf(StickerPanelTab.RECENT) }
    var selectedPackId by remember { mutableStateOf<String?>(null) }
    var pendingEditorUri by remember { mutableStateOf<Uri?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }
    var showingEmojis by remember { mutableStateOf(false) }
    var emojiQuery by remember { mutableStateOf("") }

    val imagePickerLauncher = conectate.com.presentation.components.rememberInAppMediaPicker() { uri ->
        if (uri != null) {
            pendingEditorUri = uri
        }
    }

    LaunchedEffect(packs) {
        if (selectedPackId == null && packs.isNotEmpty()) {
            selectedPackId = packs.first().id
        }
    }

    LaunchedEffect(actionMessage) {
        val msg = actionMessage?.trim().orEmpty()
        if (msg.isNotBlank()) {
            snackbarHostState?.showSnackbar(msg)
            model.clearActionMessage()
        }
    }

    val packItems = remember(results, selectedPackId) {
        val activePack = selectedPackId
        results.filter { item -> activePack == null || item.pack_id == activePack }
    }
    val tabItems = remember(selectedTab, recents, favorites, packItems, results) {
        StickerService.resolveItemsForTab(
            tab = selectedTab,
            recents = recents,
            favorites = favorites,
            packsItems = packItems,
            catalogItems = results
        )
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onEmojiSelected != null) {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = showingEmojis,
                            onClick = { showingEmojis = true; searchExpanded = false },
                            label = { Text("Emojis", maxLines = 1, softWrap = false) },
                            leadingIcon = { Icon(Icons.Default.EmojiEmotions, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        FilterChip(
                            selected = !showingEmojis,
                            onClick = { showingEmojis = false },
                            label = { Text("Stickers", maxLines = 1, softWrap = false) },
                            leadingIcon = { Icon(Icons.Default.StickyNote2, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    if (showingEmojis) {
                        IconButton(onClick = { searchExpanded = !searchExpanded }) {
                            Icon(Icons.Default.Search, "Buscar emojis")
                        }
                    }
                    if (showingEmojis && onEmojiBackspace != null) {
                        IconButton(onClick = onEmojiBackspace, modifier = Modifier.testTag("chat_emoji_backspace")) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, "Borrar último carácter o emoji")
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Keyboard, "Volver al teclado")
                    }
                } else {
                    StickerCategoryTabs(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                    IconButton(onClick = { searchExpanded = !searchExpanded }) {
                        Icon(Icons.Default.Search, "Buscar stickers")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Keyboard, "Volver al teclado")
                    }
                }
            }
            if (!showingEmojis && onEmojiSelected != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StickerCategoryTabs(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                    IconButton(onClick = { searchExpanded = !searchExpanded }) {
                        Icon(Icons.Default.Search, "Buscar stickers")
                    }
                }
            }
            if (showingEmojis) {
                if (searchExpanded) OutlinedTextField(
                    value = emojiQuery,
                    onValueChange = { emojiQuery = it },
                    label = { Text("Buscar emoji") },
                    trailingIcon = {
                        if (emojiQuery.isNotEmpty()) IconButton(onClick = { emojiQuery = "" }) {
                            Icon(Icons.Default.Close, "Borrar búsqueda de emojis")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("chat_emoji_search"),
                )
                ChatEmojiGrid(
                    query = if (searchExpanded) emojiQuery else "",
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("chat_emoji_grid"),
                    onEmojiSelected = { emoji -> onEmojiSelected?.invoke(emoji) },
                )
            } else if (searchExpanded) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { model.updateQuery(it) },
                    label = { Text("Buscar sticker") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!showingEmojis && selectedTab == StickerPanelTab.PACKS) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(packs, key = { it.id }) { pack ->
                        FilterChip(
                            selected = selectedPackId == pack.id,
                            onClick = { selectedPackId = pack.id },
                            label = { Text(pack.name) }
                        )
                    }
                }
            }

            if (!showingEmojis && isLoading) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            if (!showingEmojis) StickerGrid(
                stickers = tabItems,
                isFavorite = { sticker -> model.isFavorite(sticker) },
                onFavoriteChange = { sticker, favorite -> model.setFavorite(sticker, favorite) },
                onStickerClick = { sticker ->
                    model.recordStickerSelection(sticker)
                    onStickerSelected(sticker)
                },
                onCreateClick = { imagePickerLauncher.launch("image/*") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }

    pendingEditorUri?.let { selectedUri ->
        StickerEditor(
            imageUri = selectedUri,
            onDismiss = { pendingEditorUri = null },
            isSaving = isLoading,
            onSave = { pngBytes, title ->
                model.createStickerFromEditedBytes(
                    imageBytes = pngBytes,
                    preferredPackId = selectedPackId,
                    title = title
                ) { created ->
                    pendingEditorUri = null
                    if (created != null) {
                        model.recordStickerSelection(created)
                        onStickerSelected(created)
                    }
                }
            }
        )
    }
}

@Composable
internal fun ChatEmojiGrid(
    query: String,
    modifier: Modifier = Modifier,
    onEmojiSelected: (String) -> Unit,
) {
    val emojis = remember(query) { searchablePickerEmojis(query) }
    val emojiCellSize = 56.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    if (emojis.isEmpty()) {
        Text("No hay emojis que coincidan", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(emojiCellSize),
            contentPadding = PaddingValues(4.dp),
            modifier = modifier,
        ) {
            items(emojis, key = { it.first }) { (emoji, description) ->
                TextButton(
                    onClick = { onEmojiSelected(emoji) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(emojiCellSize).semantics { contentDescription = description },
                ) {
                    Text(
                        emoji,
                        style = TextStyle(fontSize = 28.sp, lineHeight = 36.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = true)),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
