package conectate.com.presentation.calls

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissedOutgoing
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import conectate.com.data.model.CallHistoryDirection
import conectate.com.data.model.CallHistoryEntry
import conectate.com.data.model.CallHistoryStatus
import conectate.com.data.repository.CallHistoryRepository
import conectate.com.data.repository.UserRepository
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import conectate.com.data.localfallback.UbuntuStorageUrl

enum class CallHistoryFilter(val label: String) {
    ALL("Todo"),
    DM("Chats"),
    GROUP("Grupos"),
    COMMUNITY("Comunidades")
}

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val repository: CallHistoryRepository,
    private val userRepository: UserRepository
) : ViewModel() {
    private val _filter = MutableStateFlow(CallHistoryFilter.ALL)
    val filter: StateFlow<CallHistoryFilter> = _filter.asStateFlow()
    private val _profileHints = MutableStateFlow<Map<String, CallHistoryProfileHint>>(emptyMap())

    val entries = combine(repository.entries, _filter, _profileHints) { entries, filter, hints ->
        entries.filter { entry ->
            when (filter) {
                CallHistoryFilter.ALL -> true
                CallHistoryFilter.DM -> entry.contextType.equals("dm", ignoreCase = true)
                CallHistoryFilter.GROUP -> entry.contextType.equals("group", ignoreCase = true)
                CallHistoryFilter.COMMUNITY -> entry.contextType.equals("community", ignoreCase = true)
            }
        }.map { entry ->
            val peerId = entry.peerUserId?.trim()?.lowercase().orEmpty()
            val hint = hints[peerId]
            if (hint == null) {
                entry.copy(peerAvatarUrl = UbuntuStorageUrl.rewrite(entry.peerAvatarUrl))
            } else {
                entry.copy(
                    peerName = entry.peerName?.takeIf { it.isNotBlank() } ?: hint.name,
                    peerAvatarUrl = UbuntuStorageUrl.rewrite(
                        entry.peerAvatarUrl?.takeIf { it.isNotBlank() } ?: hint.avatarUrl
                    )
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            repository.entries
                .onEach { entries ->
                    entries
                        .mapNotNull { it.peerUserId?.trim()?.lowercase()?.takeIf { id -> id.isNotBlank() } }
                        .distinct()
                        .filterNot { _profileHints.value.containsKey(it) }
                        .forEach { userId ->
                            val user = userRepository.getUserById(userId).getOrNull()
                            if (user != null) {
                                _profileHints.value = _profileHints.value + (
                                    userId to CallHistoryProfileHint(
                                        name = user.displayName.ifBlank { user.username },
                                        avatarUrl = UbuntuStorageUrl.rewrite(user.avatar_url)
                                    )
                                )
                            }
                        }
                }
                .collect { }
        }
    }

    fun updateFilter(value: String) {
        _filter.value = CallHistoryFilter.entries.firstOrNull { it.name == value } ?: CallHistoryFilter.ALL
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            repository.delete(entryId)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }
}

private data class CallHistoryProfileHint(
    val name: String?,
    val avatarUrl: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    navController: NavController,
    viewModel: CallHistoryViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    val filter by viewModel.filter.collectAsState()
    var entryPendingDelete by remember { mutableStateOf<CallHistoryEntry?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var redialEntry by remember { mutableStateOf<CallHistoryEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial de llamadas", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Borrar historial")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            IncomingCallPermissionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CallHistoryFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { viewModel.updateFilter(item.name) },
                        label = { Text(item.label) }
                    )
                }
            }

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Aún no hay llamadas registradas.",
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        items = entries,
                        key = { index, entry -> "${entry.id}:${entry.callId}:$index" }
                    ) { _, entry ->
                        CallHistoryRow(
                            entry = entry,
                            onDelete = { entryPendingDelete = entry },
                            onRedial = if (!entry.peerUserId.isNullOrBlank() &&
                                entry.contextType.trim().lowercase() !in setOf("group", "community")) {
                                { redialEntry = entry }
                            } else null,
                            onClick = {
                                when (entry.contextType.trim().lowercase()) {
                                    "group" -> entry.contextId?.let {
                                        navController.navigate(Screen.GroupChat.createRoute(it))
                                    }
                                    "community" -> entry.contextId?.let {
                                        navController.navigate(Screen.CommunityChat.createRoute(it))
                                    }
                                    else -> {
                                        val target = entry.contextId?.takeIf { it.isNotBlank() } ?: entry.peerUserId
                                        if (!target.isNullOrBlank()) {
                                            navController.navigate(Screen.Chat.createRoute(target))
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    redialEntry?.let { entry ->
        AlertDialog(onDismissRequest = { redialEntry = null }, title = { Text("Volver a llamar") },
            text = { Text("${entry.peerName ?: entry.title}: elige una llamada de voz o una videollamada.") },
            confirmButton = {
                Row {
                    CallActionButtons(navController = navController, title = entry.peerName ?: entry.title,
                        invitedUserIds = listOfNotNull(entry.peerUserId), contextType = "dm", contextId = entry.contextId,
                        onCallStarted = { redialEntry = null })
                }
            },
            dismissButton = { TextButton(onClick = { redialEntry = null }) { Text("Cancelar") } })
    }

    entryPendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryPendingDelete = null },
            title = { Text("Eliminar llamada") },
            text = { Text("¿Quieres eliminar esta llamada del historial?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(entry.id)
                    entryPendingDelete = null
                }) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { entryPendingDelete = null }) { Text("Cancelar") }
            }
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Borrar historial") },
            text = { Text("¿Quieres borrar todo el historial de llamadas?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearAllDialog = false
                }) { Text("Borrar todo") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun CallHistoryRow(
    entry: CallHistoryEntry,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onRedial: (() -> Unit)? = null,
) {
    val (icon, tint) = when (entry.status) {
        CallHistoryStatus.ANSWERED, CallHistoryStatus.ENDED ->
            Icons.Default.Call to Color(0xFF2E7D32)
        CallHistoryStatus.REJECTED ->
            Icons.Default.PhoneDisabled to MaterialTheme.colorScheme.error
        CallHistoryStatus.CANCELLED ->
            Icons.Default.CallEnd to Color(0xFFF57C00)
        CallHistoryStatus.NO_ANSWER ->
            Icons.AutoMirrored.Filled.CallMissedOutgoing to MaterialTheme.colorScheme.error
    }

    val scopeLabel = when (entry.contextType.trim().lowercase()) {
        "group" -> "Grupo"
        "community" -> "Comunidad"
        else -> "Chat"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProfileImage(
            imageUrl = entry.peerAvatarUrl,
            size = 46.dp,
            showBorder = false
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { entry.peerName ?: "Llamada" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$scopeLabel · ${entry.subtitle}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRedial != null) IconButton(onClick = onRedial) {
                    Icon(Icons.Default.Call, "Volver a llamar")
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Eliminar llamada",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = if (entry.direction == CallHistoryDirection.OUTGOING) {
                        Icons.AutoMirrored.Filled.CallMade
                    } else {
                        Icons.Default.Call
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            AssistChip(
                onClick = onClick,
                label = {
                    Text(
                        DateUtils.getRelativeTimeSpanString(
                            entry.timestampEpochMs,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString()
                    )
                }
            )
        }
    }
}






