package conectate.com.presentation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
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
    onNavigateToChatSettings: () -> Unit,
    onNavigateToCallHistory: () -> Unit,
    onNavigateToAccountSettings: () -> Unit,
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
    
    val tabs = listOf("Mensajes", "Grupos", "Canales", "Voz")
    var showInboxMenu by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf(ChatFilter.ALL) }

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
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "Bandeja de entrada",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateToAccountSettings) {
                            Icon(Icons.Outlined.ManageAccounts, null)
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSearch) {
                            Icon(Icons.Outlined.Search, null)
                        }
                        IconButton(onClick = { showInboxMenu = true }) {
                            Icon(Icons.Outlined.Tune, null)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Modern Pill-style Tabs (Threads/Instagram style)
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    edgePadding = 16.dp,
                    divider = {},
                    indicator = {},
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        val unreadCount = when(index) {
                            0 -> unreadDms
                            1 -> unreadGroups
                            2 -> unreadCommunities
                            else -> 0
                        }

                        Tab(
                            selected = isSelected,
                            onClick = { viewModel.onTabSelected(index) },
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.onSurface 
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = title,
                                    color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                if (unreadCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .size(18.dp)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
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

            FloatingActionButton(
                onClick = fabAction,
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(Icons.Default.Add, null)
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
                    topBarFilter = activeFilter,
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
