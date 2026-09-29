package conectate.com.presentation.messages.stickers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.hilt.navigation.compose.hiltViewModel
import conectate.com.data.model.StickerItem
import conectate.com.presentation.messages.StickerPickerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    onStickerSelected: (StickerItem) -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
    viewModel: StickerPickerViewModel = hiltViewModel()
) {
    if (!visible) return

    val query by viewModel.query.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val favorites by viewModel.favoriteItems.collectAsState()
    val recents by viewModel.recentItems.collectAsState()
    val packs by viewModel.packs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()

    var selectedTab by remember { mutableStateOf(StickerPanelTab.RECENT) }
    var selectedPackId by remember { mutableStateOf<String?>(null) }
    var pendingEditorUri by remember { mutableStateOf<Uri?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
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
            viewModel.clearActionMessage()
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            if (searchExpanded) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.updateQuery(it) },
                    label = { Text("Buscar sticker") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (selectedTab == StickerPanelTab.PACKS) {
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

            if (isLoading) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            StickerGrid(
                stickers = tabItems,
                isFavorite = { sticker -> viewModel.isFavorite(sticker) },
                onFavoriteChange = { sticker, favorite -> viewModel.setFavorite(sticker, favorite) },
                onStickerClick = { sticker ->
                    viewModel.recordStickerSelection(sticker)
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
                viewModel.createStickerFromEditedBytes(
                    imageBytes = pngBytes,
                    preferredPackId = selectedPackId,
                    title = title
                ) { created ->
                    pendingEditorUri = null
                    if (created != null) {
                        viewModel.recordStickerSelection(created)
                        onStickerSelected(created)
                    }
                }
            }
        )
    }
}
