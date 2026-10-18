package conectate.com.presentation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import conectate.com.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovvinChatScreen(
    onBack: () -> Unit,
    viewModel: NovvinChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val isHistoryLoading by viewModel.isHistoryLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val canRetry by viewModel.canRetry.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var draft by rememberSaveable(currentConversationId) { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    fun submitDraft() {
        val value = draft.trim()
        if (value.isNotEmpty() && !isThinking && !isHistoryLoading) {
            viewModel.send(value)
            draft = ""
        }
    }

    LaunchedEffect(messages.lastOrNull()?.id, isThinking) {
        if (messages.isNotEmpty()) {
            val targetIndex = if (messages.size == 1) 1 else messages.lastIndex
            runCatching { listState.animateScrollToItem(targetIndex) }
        }
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            val result = snackbarHostState.showSnackbar(
                message = it,
                actionLabel = if (canRetry) "Reintentar" else null,
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.retryLastMessage()
            viewModel.clearError()
        }
    }

    if (showHistory) {
        NovvinHistorySheet(
            conversations = conversations,
            selectedId = currentConversationId,
            onSelect = {
                viewModel.selectConversation(it)
                showHistory = false
            },
            onDelete = viewModel::deleteConversation,
            onNewChat = {
                viewModel.startNewChat()
                showHistory = false
            },
            onDismiss = { showHistory = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            conectate.com.presentation.common.KeyboardSafeSnackbarHost(snackbarHostState)
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_novvin),
                            contentDescription = "Logo de Novvin",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(38.dp)
                        )
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("Novvin", fontWeight = FontWeight.Bold)
                            Text(
                                if (isThinking) "Pensando..." else "IA de Conectate",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = "Chats anteriores")
                    }
                    IconButton(onClick = viewModel::startNewChat) {
                        Icon(Icons.Default.AddComment, contentDescription = "Nuevo chat")
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = draft,
                        visualTransformation = conectate.com.presentation.components.rememberChatSpellCheck(draft),
                        onValueChange = { draft = it.take(1000) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Escribe a Novvin") },
                        enabled = !isHistoryLoading,
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { submitDraft() }),
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { submitDraft() },
                        enabled = draft.isNotBlank() && !isThinking && !isHistoryLoading,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (messages.size == 1) {
                item(key = "novvin-mark") { NovvinMark() }
            }
            items(messages, key = { it.id }) { message ->
                NovvinBubble(message = message, onSuggestion = viewModel::send)
            }
            if (isThinking) {
                item(key = "novvin-thinking") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Novvin esta pensando...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovvinHistorySheet(
    conversations: List<NovvinConversation>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd/MM, HH:mm", Locale.getDefault()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chats de Novvin", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.AddComment, contentDescription = "Nuevo chat")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(conversations, key = { it.id }) { conversation ->
                val selected = conversation.id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onSelect(conversation.id) }
                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            conversation.title,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            dateFormat.format(Date(conversation.updatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onDelete(conversation.id) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar chat")
                    }
                }
            }
        }
    }
}

@Composable
private fun NovvinMark() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_novvin),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(112.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("Novvin", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "La IA propia de Conectate",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NovvinBubble(message: NovvinMessage, onSuggestion: (String) -> Unit) {
    val horizontalAlignment = if (message.fromNovvin) Alignment.Start else Alignment.End
    val background = if (message.fromNovvin) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
    val foreground = if (message.fromNovvin) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = horizontalAlignment) {
        if (message.fromNovvin) {
            Row(
                modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_novvin),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Novvin",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = message.text,
            color = foreground,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(background)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        if (message.fromNovvin && message.suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                message.suggestions.take(3).forEach { suggestion ->
                    AssistChip(
                        onClick = { onSuggestion(suggestion) },
                        label = { Text(suggestion, maxLines = 1) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }
}
