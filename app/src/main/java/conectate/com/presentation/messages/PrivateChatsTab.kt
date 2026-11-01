package conectate.com.presentation.messages

import androidx.compose.runtime.Composable
import androidx.activity.compose.ReportDrawnWhen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import conectate.com.data.model.ChatWithLastMessage

// Colors now resolved at runtime via MaterialTheme.colorScheme for light/dark compatibility

/**
 * Tab de Chats Privados (1:1)
 * Reutiliza toda la lógica existente sin modificaciones
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatsTab(
    onNavigateToSearch: () -> Unit,
    onNavigateToCallHistory: () -> Unit,
    onOpenSavedMessages: () -> Unit = {},
    viewModel: MessagingViewModel = hiltViewModel(),
    onNavigateToChat: (String) -> Unit
) {
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    // Measure usable inbox content, rather than treating the splash as a complete launch.
    ReportDrawnWhen { conversationsState !is ConversationsUiState.Loading }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val folders by viewModel.chatFolders.collectAsState()
    val selectedFolderId by viewModel.selectedFolderId.collectAsState()
    val selectedListFilter by viewModel.selectedListFilter.collectAsState()
    val uiMessage by viewModel.uiMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showManageFoldersDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var folderTargetConversation by remember { mutableStateOf<ChatWithLastMessage?>(null) }
    var folderPendingDeletion by remember { mutableStateOf<conectate.com.data.model.ChatFolder?>(null) }

    LaunchedEffect(uiMessage) {
        val message = uiMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeUiMessage()
    }

    val colorScheme = MaterialTheme.colorScheme
    val inboxRed = colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InboxSearchField(
                query = searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                modifier = Modifier.weight(1f),
            )
            InboxSearchActions(
                onNavigateToCallHistory,
                onOpenSavedMessages,
                inboxRed,
                colorScheme.onPrimary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InboxFilterPill(
                selected = selectedFolderId == null && selectedListFilter == ChatListFilter.ALL,
                onClick = { viewModel.selectListFilter(ChatListFilter.ALL) },
                label = "Todas",
            )
            InboxFilterPill(
                selected = selectedListFilter == ChatListFilter.FAVORITES,
                onClick = { viewModel.selectListFilter(ChatListFilter.FAVORITES) },
                label = "Favoritos",
                icon = Icons.Default.Star,
                outlined = selectedListFilter != ChatListFilter.FAVORITES,
            )
            InboxFilterPill(
                selected = selectedListFilter == ChatListFilter.ARCHIVED,
                onClick = { viewModel.selectListFilter(ChatListFilter.ARCHIVED) },
                label = "Archivados",
                icon = Icons.Default.Archive,
                outlined = selectedListFilter != ChatListFilter.ARCHIVED,
            )
            InboxFilterPill(
                onClick = { showCreateFolderDialog = true },
                label = "Nueva carpeta",
                icon = Icons.Default.CreateNewFolder,
                outlined = true,
            )
            folders.forEach { folder ->
                InboxFilterPill(
                    selected = selectedFolderId == folder.id,
                    onClick = { viewModel.selectFolder(folder.id) },
                    label = folder.name,
                    icon = Icons.Default.Folder,
                    outlined = selectedFolderId != folder.id,
                )
            }
            if (folders.isNotEmpty()) {
                InboxFilterPill(
                    onClick = { showManageFoldersDialog = true },
                    label = "Gestionar",
                    icon = Icons.Default.Delete,
                    outlined = true,
                )
            }
        }

        // Contenido según estado
        when (val state = conversationsState) {
            is ConversationsUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = inboxRed
                    )
                }
            }
            is ConversationsUiState.Success -> {
                if (state.isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = inboxRed)
                }
                // Filtrar SOLO chats privados (no grupos)
                val privateChats = state.conversations.filter { !it.chat.is_group }
                
                if (privateChats.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = when {
                                    state.isInitialSyncPending -> "Buscando tus conversaciones…"
                                    selectedFolderId != null && searchQuery.isEmpty() ->
                                        "Esta carpeta está vacía. Agrega chats desde \"Todas\"."
                                    selectedListFilter == ChatListFilter.FAVORITES && searchQuery.isEmpty() ->
                                        "No tienes chats favoritos"
                                    selectedListFilter == ChatListFilter.ARCHIVED && searchQuery.isEmpty() ->
                                        "No tienes chats archivados"
                                    searchQuery.isEmpty() ->
                                        "No tienes conversaciones"
                                    else ->
                                        "No se encontraron conversaciones"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = colorScheme.onSurfaceVariant
                            )
                            
                            OutlinedButton(
                                onClick = onNavigateToSearch,
                                shape = CircleShape,
                                modifier = Modifier.height(48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.onSurface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outline)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Buscar personas nuevas")
                            }
                        }
                    }
                } else {
                    var showReportDialog by remember { mutableStateOf(false) }
                    var selectedConversation by remember { mutableStateOf<ChatWithLastMessage?>(null) }
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorScheme.background)
                    ) {
                        items(
                            items = privateChats,
                            key = { it.chat.id }
                        ) { conversation ->
                            val otherUserId = conversation.chat.participants
                                .firstOrNull { it != viewModel.currentUserId } ?: ""
                            
                            ConversationItemWithSwipe(
                                conversation = conversation,
                                currentUserId = viewModel.currentUserId ?: "",
                                onClick = {
                                onNavigateToChat(conversation.chat.id)
                                },
                                onMute = { viewModel.muteChat(conversation.chat.id, !conversation.chat.is_muted) },
                                onPin = { viewModel.pinChat(conversation.chat.id, !conversation.chat.is_pinned) },
                                onFavorite = { viewModel.favoriteChat(conversation.chat.id, !conversation.chat.is_favorite) },
                                onArchive = { viewModel.archiveChat(conversation.chat.id, !conversation.chat.is_archived) },
                                onBlock = { viewModel.blockUser(otherUserId) },
                                onManageFolders = { folderTargetConversation = conversation },
                                onDelete = { viewModel.deleteChat(conversation.chat.id) },
                                onReport = {
                                    selectedConversation = conversation
                                    showReportDialog = true
                                }
                            )
                            HorizontalDivider(color = colorScheme.outlineVariant)
                        }
                    }

                    if (showReportDialog && selectedConversation != null) {
                        val conv = selectedConversation!!
                        val reportedUserId = conv.chat.participants.firstOrNull { it != viewModel.currentUserId } ?: ""
                        val reportedUserName = conv.otherUserName ?: "Usuario"
                        conectate.com.presentation.components.ReportDialog(
                            reportedUserId = reportedUserId,
                            reportedUserName = reportedUserName,
                            onDismiss = { showReportDialog = false },
                            onSubmit = { reason, description, evidenceUri ->
                                viewModel.submitReport(reportedUserId, reportedUserName, reason, description, evidenceUri)
                            }
                        )
                    }
                }
            }
            is ConversationsUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = state.message,
                            color = colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        FilledTonalButton(onClick = viewModel::refreshNow) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Reintentar")
                        }
                    }
                }
            }
        }
        }
        conectate.com.presentation.common.KeyboardSafeSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
        )

        if (showCreateFolderDialog) {
            AlertDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = { Text("Nueva carpeta") },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it.take(60) },
                        label = { Text("Nombre de carpeta") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newFolderName.isNotBlank()) {
                            viewModel.createFolder(newFolderName)
                            newFolderName = ""
                        }
                        showCreateFolderDialog = false
                    }) {
                        Text("Crear")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateFolderDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }

        if (showManageFoldersDialog) {
            AlertDialog(
                onDismissRequest = { showManageFoldersDialog = false },
                title = { Text("Gestionar carpetas") },
                text = {
                    if (folders.isEmpty()) {
                        Text("No hay carpetas creadas.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            folders.forEach { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = folder.name,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { folderPendingDeletion = folder }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Eliminar carpeta"
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showManageFoldersDialog = false }) {
                        Text("Cerrar")
                    }
                }
            )
        }

        if (folderPendingDeletion != null) {
            val folder = folderPendingDeletion!!
            AlertDialog(
                onDismissRequest = { folderPendingDeletion = null },
                title = { Text("Eliminar carpeta") },
                text = {
                    Text("¿Seguro que quieres eliminar \"${folder.name}\"? Los chats no se borrarán, solo la carpeta.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteFolder(folder.id)
                        folderPendingDeletion = null
                    }) {
                        Text("Eliminar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { folderPendingDeletion = null }) {
                        Text("Cancelar")
                    }
                }
            )
        }

        if (folderTargetConversation != null) {
            val targetConversation = folderTargetConversation!!
            val normalizedChatId = targetConversation.chat.id.trim().lowercase()
            AlertDialog(
                onDismissRequest = { folderTargetConversation = null },
                title = { Text("Añadir a carpeta") },
                text = {
                    if (folders.isEmpty()) {
                        Text("No hay carpetas creadas. Crea una carpeta primero.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            folders.forEach { folder ->
                                val inFolder = folder.chat_ids.any { it == normalizedChatId }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (inFolder) {
                                                viewModel.removeChatFromFolder(folder.id, targetConversation.chat.id)
                                            } else {
                                                viewModel.addChatToFolder(folder.id, targetConversation.chat.id)
                                            }
                                            folderTargetConversation = null
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (inFolder) Icons.Default.Check else Icons.Default.Folder,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(folder.name)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { folderTargetConversation = null }) {
                        Text("Cerrar")
                    }
                },
                dismissButton = {
                    if (folders.isEmpty()) {
                        TextButton(onClick = {
                            folderTargetConversation = null
                            showCreateFolderDialog = true
                        }) {
                            Text("Crear carpeta")
                        }
                    }
                }
            )
        }
    }
}

@Composable
internal fun InboxSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.heightIn(min = 56.dp).testTag("inbox_conversation_search"),
        placeholder = {
            Text(
                "Buscar conversaciones",
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Buscar",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(21.dp),
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.onBackground,
            unfocusedTextColor = colors.onBackground,
            cursorColor = colors.primary,
            focusedBorderColor = colors.outline,
            unfocusedBorderColor = colors.outlineVariant,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
        ),
    )
}

@Composable
private fun InboxSearchActions(
    onNavigateToCallHistory: () -> Unit,
    onOpenSavedMessages: () -> Unit,
    accent: Color,
    onAccent: Color,
) {
    FilledTonalIconButton(
        onClick = onNavigateToCallHistory,
        modifier = Modifier.size(46.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = accent,
            contentColor = onAccent,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.Call,
            contentDescription = "Abrir historial de llamadas",
            modifier = Modifier.size(21.dp),
        )
    }
    IconButton(onClick = onOpenSavedMessages, modifier = Modifier.size(46.dp)) {
        Icon(Icons.Default.Bookmark, contentDescription = "Abrir mensajes guardados")
    }
}

@Composable
private fun InboxFilterPill(
    selected: Boolean = false,
    onClick: () -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    outlined: Boolean = false,
    height: Dp = 32.dp,
) {
    val colorSchemePill = MaterialTheme.colorScheme
    val pillAccent = colorSchemePill.primary
    val background = when {
        selected -> pillAccent
        outlined -> Color.Transparent
        else -> colorSchemePill.surfaceVariant
    }
    val borderColor = if (selected) pillAccent else colorSchemePill.outline
    Surface(
        onClick = onClick,
        color = background,
        contentColor = if (selected) colorSchemePill.onPrimary else colorSchemePill.onSurfaceVariant,
        shape = RoundedCornerShape(9.dp),
        border = if (outlined || !selected) androidx.compose.foundation.BorderStroke(1.dp, borderColor) else null,
        modifier = Modifier.height(height)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) colorSchemePill.onPrimary else pillAccent,
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                text = label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}
