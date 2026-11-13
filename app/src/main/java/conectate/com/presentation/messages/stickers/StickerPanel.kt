package conectate.com.presentation.messages.stickers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AddPhotoAlternate
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
    // Callers keep this composable mounted while hidden, so both the active
    // section and each section's scroll survive switching back to the keyboard.
    var selectedTab by rememberSaveable { mutableStateOf(StickerPanelTab.RECENT) }
    var selectedPackId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var showingEmojis by rememberSaveable { mutableStateOf(false) }
    var emojiQuery by rememberSaveable { mutableStateOf("") }
    val sectionStates = rememberSaveableStateHolder()
    if (!visible) return
    val model = viewModel ?: hiltViewModel<StickerPickerViewModel>()

    val query by model.query.collectAsState()
    val results by model.searchResults.collectAsState()
    val favorites by model.favoriteItems.collectAsState()
    val recents by model.recentItems.collectAsState()
    val packs by model.packs.collectAsState()
    val isLoading by model.isLoading.collectAsState()
    val actionMessage by model.actionMessage.collectAsState()

    var pendingEditorUri by remember { mutableStateOf<Uri?>(null) }

    val imagePickerLauncher = conectate.com.presentation.components.rememberInAppMediaPicker() { uri ->
        if (uri != null) {
            pendingEditorUri = uri
        }
    }

    LaunchedEffect(packs) {
        if (packs.isNotEmpty() && packs.none { it.id == selectedPackId }) {
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
    val tabItems = remember(selectedTab, recents, favorites, packItems, results, searchExpanded, query) {
        StickerService.resolveItemsForTab(
            tab = if (searchExpanded && query.isNotBlank()) StickerPanelTab.CATALOG else selectedTab,
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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onEmojiSelected != null) {
                    IconToggleButton(
                        checked = showingEmojis,
                        onCheckedChange = { showingEmojis = true },
                        modifier = Modifier.testTag("picker_emoji_category"),
                    ) {
                        Icon(Icons.Default.EmojiEmotions, "Emojis",
                            tint = if (showingEmojis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconToggleButton(
                        checked = !showingEmojis,
                        onCheckedChange = { showingEmojis = false },
                        modifier = Modifier.testTag("picker_sticker_category"),
                    ) {
                        Icon(Icons.Default.StickyNote2, "Stickers",
                            tint = if (!showingEmojis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(Modifier.weight(1f))
                IconButton(onClick = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) model.updateQuery("")
                }) {
                    Icon(Icons.Default.Search, if (showingEmojis) "Buscar emojis" else "Buscar stickers")
                }
                if (showingEmojis && onEmojiBackspace != null) {
                    IconButton(onClick = onEmojiBackspace, modifier = Modifier.testTag("chat_emoji_backspace")) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, "Borrar último carácter o emoji")
                    }
                } else if (!showingEmojis) {
                    IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.AddPhotoAlternate, "Crear sticker")
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Keyboard, "Volver al teclado")
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
                Box(Modifier.fillMaxWidth().weight(1f).testTag("chat_emoji_grid")) {
                    sectionStates.SaveableStateProvider("emojis") {
                        ChatEmojiGrid(
                            query = if (searchExpanded) emojiQuery else "",
                            modifier = Modifier.fillMaxSize(),
                            onEmojiSelected = { emoji -> onEmojiSelected?.invoke(emoji) },
                        )
                    }
                }
            } else if (searchExpanded) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { model.updateQuery(it) },
                    label = { Text("Buscar sticker") },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { model.updateQuery("") }) {
                            Icon(Icons.Default.Close, "Borrar búsqueda de stickers")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!showingEmojis) {
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (isLoading) LinearProgressIndicator(Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxWidth().weight(1f).testTag("chat_sticker_grid")) {
                    val sectionKey = "stickers:${selectedTab.name}:${if (selectedTab == StickerPanelTab.PACKS) selectedPackId else ""}"
                    sectionStates.SaveableStateProvider(sectionKey) {
                        StickerGrid(
                            stickers = tabItems,
                            isFavorite = { sticker -> model.isFavorite(sticker) },
                            onFavoriteChange = { sticker, favorite -> model.setFavorite(sticker, favorite) },
                            onStickerClick = { sticker ->
                                model.recordStickerSelection(sticker)
                                onStickerSelected(sticker)
                            },
                            onCreateClick = null, // Kept in the header without using a grid cell.
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().testTag("sticker_bottom_categories"), verticalAlignment = Alignment.CenterVertically) {
                    StickerCategoryTabs(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = if (selectedTab == StickerPanelTab.PACKS && packs.isNotEmpty()) Modifier.width(216.dp) else Modifier.weight(1f),
                        compact = true,
                    )
                    if (selectedTab == StickerPanelTab.PACKS && packs.isNotEmpty()) {
                        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(packs, key = { it.id }) { pack ->
                                FilterChip(selected = selectedPackId == pack.id,
                                    onClick = { selectedPackId = pack.id },
                                    label = { Text(pack.name, maxLines = 1, softWrap = false) })
                            }
                        }
                    }
                }
            }
        }
    }

    pendingEditorUri?.let { selectedUri ->
        StickerEditor(
            imageUri = selectedUri,
            onDismiss = { pendingEditorUri = null },
            isSaving = isLoading,
            onSendOriginal = { title ->
                model.createStickerFromImage(selectedUri, selectedPackId, title) { created ->
                    pendingEditorUri = null
                    model.recordStickerSelection(created)
                    onStickerSelected(created)
                }
            },
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
