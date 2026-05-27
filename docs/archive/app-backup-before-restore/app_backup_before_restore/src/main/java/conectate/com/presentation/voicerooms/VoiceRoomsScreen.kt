package conectate.com.presentation.voicerooms

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.model.VoiceRoom
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRoomsScreen(
    @Suppress("UNUSED_PARAMETER") navController: NavController,
    viewModel: VoiceRoomsViewModel = hiltViewModel()
) {
    val roomsState by viewModel.uiState.collectAsState()
    val isCreatingRoom by viewModel.isCreatingRoom.collectAsState()
    val hasAccess by viewModel.hasAccess.collectAsState()
    val requestStatus by viewModel.accessRequestStatus.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Salas de Voz", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { navController.navigate(conectate.com.presentation.navigation.Screen.VoiceRoomSearch.route) }) {
                        Icon(Icons.Default.Search, contentDescription = "Buscar sala", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            if (hasAccess) {
                FloatingActionButton(
                    onClick = { navController.navigate(conectate.com.presentation.navigation.Screen.CreateVoiceRoom.route) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Crear sala",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    ) { padding ->
        if (!hasAccess) {
            Box(modifier = Modifier.padding(padding)) {
                RestrictedAccessScreen(
                    requestStatus = requestStatus,
                    onRequestAccess = { viewModel.requestAccess() }
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
            when (val state = roomsState) {
                is VoiceRoomsUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                is VoiceRoomsUiState.Success -> {
                    if (state.rooms.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VoiceChat,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                                )
                                Text(
                                    text = "No hay salas activas",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "Crea una nueva sala para comenzar",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                )
                            }
                        }
                    } else {
                        val isAdmin by viewModel.isAdmin.collectAsState()
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.rooms) { room ->
                                val isCreator = room.creatorId == viewModel.getCurrentUserId()
                                val activeRoomId = viewModel.getActiveRoomId()
                                RoomCard(
                                    room = room,
                                    isActive = room.id == activeRoomId,
                                    onClick = {
                                        navController.navigate(conectate.com.presentation.navigation.Screen.RoomDetail.createRoute(room.id))
                                    },
                                    onDelete = if (isCreator || isAdmin) { { viewModel.deleteRoom(room.id) } } else null
                                )
                            }
                        }
                    }
                }
                
                is VoiceRoomsUiState.SearchResult -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                
                is VoiceRoomsUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Error al cargar salas",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomCard(
    room: VoiceRoom,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = room.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "Por ${room.creatorName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                
                // Live indicator or Active call indicator
                Column(horizontalAlignment = Alignment.End) {
                    if (isActive) {
                        // Show "En llamada" badge in green
                        Surface(
                            color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "EN LLAMADA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        // Show "En vivo" badge
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Circle,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.size(8.dp)
                                )
                                Text(
                                    text = "EN VIVO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    
                    if (onDelete != null) {
                         IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Eliminar sala",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            
            if (room.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = room.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "${room.participantCount} participantes",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "${room.speakerIds.size} hablando",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}


