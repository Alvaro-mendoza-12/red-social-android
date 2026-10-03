package conectate.com.presentation.messages

import androidx.compose.runtime.Composable

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import conectate.com.presentation.voicerooms.RestrictedAccessScreen

/**
 * Tab de Salas de Voz (NUEVO - Fase 4)
 */
@Composable
fun VoiceRoomsTab(
    navController: NavController,
    viewModel: VoiceRoomsViewModel = hiltViewModel(),
    onNavigateToCreateRoom: () -> Unit,
    onNavigateToRoomDetail: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val hasAccess by viewModel.hasAccess.collectAsState()
    val requestStatus by viewModel.accessRequestStatus.collectAsState()
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStatus()
                viewModel.refreshRooms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.errorEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    var showSearchDialog by remember { mutableStateOf(false) }
    var searchId by remember { mutableStateOf("") }
    val searchState by viewModel.searchState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (!hasAccess) {
            RestrictedAccessScreen(
                requestStatus = requestStatus ?: "",
                onRequestAccess = { viewModel.requestAccess() },
                onRefresh = { viewModel.refreshStatus() }
            )
        } else {
            // Header Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                    .padding(16.dp)
            ) {
                // ... (el contenido original del Row que estaba en las líneas 53-102)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Salas de Voz en Vivo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Únete a conversaciones en tiempo real",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                    
                    Row {
                        IconButton(onClick = { 
                            viewModel.refreshRooms()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refrescar",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        
                        IconButton(onClick = { 
                            viewModel.resetSearch()
                            showSearchDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Buscar por ID",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            if (showSearchDialog) {
                VoiceRoomSearchDialog(
                    id = searchId,
                    state = searchState,
                    onIdChange = { searchId = it; viewModel.resetSearch() },
                    onSearch = { viewModel.searchRoomById(searchId) },
                    onDismiss = { showSearchDialog = false; viewModel.resetSearch() },
                )
            }

            LaunchedEffect(searchState, showSearchDialog) {
                val found = searchState as? VoiceRoomSearchState.Found
                if (showSearchDialog && found != null) {
                    showSearchDialog = false
                    viewModel.resetSearch()
                    onNavigateToRoomDetail(found.room.id)
                }
            }

            when (val state = uiState) {
                is VoiceRoomsUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is VoiceRoomsUiState.Success -> {
                    if (state.rooms.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Text(
                                    "No hay salas disponibles",
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    "Crea una ahora y empieza a hablar.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Button(onClick = {
                                    onNavigateToCreateRoom()
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Crear Sala")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 80.dp) // Space for FAB
                        ) {
                            items(state.rooms, key = { it.id }) { room ->
                                val isCreator = room.creatorId == viewModel.getCurrentUserId()
                                VoiceRoomItem(
                                    room = room,
                                    onClick = {
                                        onNavigateToRoomDetail(room.id)
                                    },
                                    onDelete = if (isCreator) { { viewModel.deleteRoom(room.id) } } else null
                                )
                            }
                        }
                    }
                }
                is VoiceRoomsUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(text = state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Button(onClick = viewModel::refreshRooms) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Reintentar")
                            }
                        }
                    }
                }
            }
        }
    }
}
