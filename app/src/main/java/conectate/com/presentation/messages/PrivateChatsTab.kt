package conectate.com.presentation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import conectate.com.data.model.ChatWithLastMessage

/**
 * Tab de Chats Privados (1:1)
 * Reutiliza toda la lógica existente sin modificaciones
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatsTab(
    onNavigateToSearch: () -> Unit,
    viewModel: MessagingViewModel = hiltViewModel(),
    topBarFilter: ChatFilter = ChatFilter.ALL,
    onNavigateToChat: (String) -> Unit
) {
    val conversationsState by viewModel.conversationsState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val folders by viewModel.chatFolders.collectAsState()
    val selectedFolderId by viewModel.selectedFolderId.collectAsState()
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

    LaunchedEffect(topBarFilter) {
        when (topBarFilter) {
            ChatFilter.ALL -> viewModel.selectFolder(null)
            ChatFilter.FAVORITES -> viewModel.selectFolder(FAVORITE_CHAT_FILTER_ID)
            ChatFilter.ARCHIVED -> viewModel.selectFolder(ARCHIVED_CHAT_FILTER_ID)
            ChatFilter.UNREAD, ChatFilter.GROUPS -> viewModel.selectFolder(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // Barra de búsqueda
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("Buscar conversaciones") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Buscar"
                )
            },
            singleLine = true,
            shape = CircleShape
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFolderId == null,
                onClick = { viewModel.selectFolder(null) },
                label = { Text("Todas") }
            )
            FilterChip(
                selected = selectedFolderId == FAVORITE_CHAT_FILTER_ID,
                onClick = {
                    viewModel.selectFolder(
                        if (selectedFolderId == FAVORITE_CHAT_FILTER_ID) null else FAVORITE_CHAT_FILTER_ID
                    )
                },
                label = { Text("Favoritos") },
                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) }
            )
            FilterChip(
                selected = selectedFolderId == ARCHIVED_CHAT_FILTER_ID,
                onClick = {
                    viewModel.selectFolder(
                        if (selectedFolderId == ARCHIVED_CHAT_FILTER_ID) null else ARCHIVED_CHAT_FILTER_ID
                    )
                },
                label = { Text("Archivados") },
                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) }
            )
            folders.forEach { folder ->
                FilterChip(
                    selected = selectedFolderId == folder.id,
                    onClick = { viewModel.selectFolder(folder.id) },
                    label = { Text(folder.name) }
                )
            }
            AssistChip(
                onClick = { showCreateFolderDialog = true },
                label = { Text("Nueva carpeta") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) }
            )
            if (folders.isNotEmpty()) {
                AssistChip(
                    onClick = { showManageFoldersDialog = true },
                    label = { Text("Gestionar") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Contenido según estado
        when (val state = conversationsState) {
            is ConversationsUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            is ConversationsUiState.Success -> {
                // Filtrar SOLO chats privados (no grupos)
                val privateChats = state.conversations
                    .filter { !it.chat.is_group }
                    .let { chats ->
                        if (topBarFilter == ChatFilter.UNREAD) chats.filter { it.unreadCount > 0 } else chats
                    }
                
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
                                    selectedFolderId != null && searchQuery.isEmpty() ->
                                        when (selectedFolderId) {
                                            ARCHIVED_CHAT_FILTER_ID -> "No tienes chats archivados."
                                            FAVORITE_CHAT_FILTER_ID -> "No tienes chats favoritos."
                                            else -> "Esta carpeta está vacía. Agrega chats desde \"Todas\"."
                                        }
                                    topBarFilter == ChatFilter.UNREAD && searchQuery.isEmpty() ->
                                        "No tienes chats privados sin leer."
                                    searchQuery.isEmpty() ->
                                        "No tienes conversaciones"
                                    else ->
                                        "No se encontraron conversaciones"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                            
                            OutlinedButton(
                                onClick = onNavigateToSearch,
                                shape = CircleShape,
                                modifier = Modifier.height(48.dp)
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
                    
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                            Divider()
                        }
                    }

                    selectedConversation?.let { conv ->
                    if (showReportDialog) {
                        val reportedUserId = conv.chat.participants.firstOrNull { it != viewModel.currentUserId } ?: ""
                        val reportedUserName = conv.otherUserName ?: "Usuario"
                        conectate.com.presentation.components.ReportDialog(
                            reportedUserName = reportedUserName,
                            onDismiss = { showReportDialog = false },
                            onSubmit = { reason, description, evidenceUri ->
                                viewModel.submitReport(reportedUserId, reportedUserName, reason, description, evidenceUri)
                                // Dialog handles confirmation within itself
                            }
                        )
                    }
                    }
                }
            }
            is ConversationsUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
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

        folderPendingDeletion?.let { folder ->
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

        folderTargetConversation?.let { targetConversation ->
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
