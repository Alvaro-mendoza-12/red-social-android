package conectate.com.presentation.calls

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissedOutgoing
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController

enum class CallHistoryFilter(val label: String) {
    ALL("Todo"),
    DM("Chats"),
    GROUP("Grupos"),
    COMMUNITY("Comunidades")
}

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    repository: CallHistoryRepository
) : ViewModel() {
    private val _filter = MutableStateFlow(CallHistoryFilter.ALL)
    val filter: StateFlow<CallHistoryFilter> = _filter.asStateFlow()

    val entries = combine(repository.entries, _filter) { entries, filter ->
        entries.filter { entry ->
            when (filter) {
                CallHistoryFilter.ALL -> true
                CallHistoryFilter.DM -> entry.contextType.equals("dm", ignoreCase = true)
                CallHistoryFilter.GROUP -> entry.contextType.equals("group", ignoreCase = true)
                CallHistoryFilter.COMMUNITY -> entry.contextType.equals("community", ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateFilter(value: String) {
        _filter.value = CallHistoryFilter.entries.firstOrNull { it.name == value } ?: CallHistoryFilter.ALL
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    navController: NavController,
    viewModel: CallHistoryViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    val filter by viewModel.filter.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial de llamadas", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                    items(items = entries, key = { it.id }) { entry ->
                        CallHistoryRow(
                            entry = entry,
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
}

@Composable
private fun CallHistoryRow(
    entry: CallHistoryEntry,
    onClick: () -> Unit
) {
    val (icon, tint) = when (entry.status) {
        CallHistoryStatus.ANSWERED, CallHistoryStatus.ENDED ->
            Icons.Default.Call to Color(0xFF2E7D32)
        CallHistoryStatus.REJECTED ->
            Icons.Default.PhoneDisabled to MaterialTheme.colorScheme.error
        CallHistoryStatus.CANCELLED ->
            Icons.Default.CallEnd to Color(0xFFF57C00)
        CallHistoryStatus.NO_ANSWER ->
            Icons.Default.CallMissedOutgoing to MaterialTheme.colorScheme.error
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
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = if (entry.direction == CallHistoryDirection.OUTGOING) {
                        Icons.Default.CallMade
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






