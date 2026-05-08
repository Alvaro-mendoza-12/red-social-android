package conectate.com.presentation.messages

import androidx.compose.runtime.Composable

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    navController: NavController,
    viewModel: MessagingViewModel = hiltViewModel(),
    voiceRoomsViewModel: VoiceRoomsViewModel = hiltViewModel(),
    onNavigateToChat: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToCreateGroup: () -> Unit,
    onNavigateToCreateCommunity: () -> Unit,
    onNavigateToCreateVoiceRoom: () -> Unit,
    onNavigateToGroupChat: (String) -> Unit,
    onNavigateToCommunityChat: (String) -> Unit,
    onNavigateToRoomDetail: (String) -> Unit
) {
    val selectedTab by viewModel.selectedTabIndex.collectAsState()
    val unreadDms by viewModel.unreadDmsCount.collectAsState()
    val unreadGroups by viewModel.unreadGroupsCount.collectAsState()
    val unreadCommunities by viewModel.unreadCommunitiesCount.collectAsState()
    val activeRoom by voiceRoomsViewModel.currentRoom.collectAsState()
    val participants by voiceRoomsViewModel.currentParticipants.collectAsState()
    val currentUserKey = viewModel.currentUserId?.trim()?.lowercase()
    val localIsMuted = participants.firstOrNull { participant ->
        currentUserKey != null && participant.id.trim().lowercase() == currentUserKey
    }?.isMuted ?: false
    
    val tabs = listOf("Chats", "Grupos", "Comunidad", "Voz")

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier.background(MaterialTheme.colorScheme.primary)
            ) {
                // Auto-height TopAppBar with system insets support
                // Custom Compact Header (48dp) instead of TopAppBar (64dp)
                Row(
                    modifier = Modifier
                        .background(Color.Transparent)
                        .statusBarsPadding()
                        .height(48.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mensajes",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Buscar conversación",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                conectate.com.presentation.components.ActiveVoiceBar(
                    activeRoom = activeRoom,
                    isMuted = localIsMuted,
                    modifier = Modifier.fillMaxWidth(),
                    onReEnter = onNavigateToRoomDetail,
                    onToggleMute = {
                        voiceRoomsViewModel.toggleMute(!localIsMuted)
                    },
                    onHangUp = {
                        voiceRoomsViewModel.leaveCurrentRoom()
                    }
                )
                
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        val unreadCount = when(index) {
                            0 -> unreadDms
                            1 -> unreadGroups
                            2 -> unreadCommunities
                            else -> 0
                        }
                        
                        Tab(
                            selected = selectedTab == index,
                            onClick = { viewModel.onTabSelected(index) },
                            selectedContentColor = Color.White,
                            unselectedContentColor = Color.White.copy(alpha = 0.7f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Visible // We use Visible to avoid clipping issues
                                ) 
                                
                                if (unreadCount > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(Color.White, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                            color = Color(0xFFE53935),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            val fabAction = when (selectedTab) {
                0 -> onNavigateToSearch
                1 -> onNavigateToCreateGroup
                2 -> onNavigateToCreateCommunity
                3 -> onNavigateToCreateVoiceRoom
                else -> { { } }
            }
            
            val fabDescription = when (selectedTab) {
                0 -> "Nuevo mensaje"
                1 -> "Crear grupo"
                2 -> "Crear comunidad"
                3 -> "Crear sala de voz"
                else -> ""
            }

            FloatingActionButton(
                onClick = fabAction,
                containerColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = fabDescription,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> PrivateChatsTab(
                    onNavigateToSearch = onNavigateToSearch,
                    viewModel = viewModel,
                    onNavigateToChat = onNavigateToChat
                )
                1 -> GroupsTab(
                    navController = navController,
                    onNavigateToGroupChat = onNavigateToGroupChat
                )
                2 -> CommunitiesTab(
                    navController = navController,
                    onNavigateToCommunityChat = onNavigateToCommunityChat
                )
                3 -> VoiceRoomsTab(
                    navController = navController,
                    onNavigateToCreateRoom = onNavigateToCreateVoiceRoom,
                    onNavigateToRoomDetail = onNavigateToRoomDetail
                )
            }
        }
    }
}

