package conectate.com.presentation.messages

import androidx.compose.runtime.Composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen

// ──────────────────────────────────────────────────────────────────────────────
// MessagesScreen — Sprint 1.1 completo
// Barra superior: Ajustes · Registro de llamadas · Buscar · Cambio de cuenta
//                 Menú (Todos / No leídos / Favoritos / Archivados)
// 4 Tabs: Chats · Grupos · Comunidades · Voz
// ──────────────────────────────────────────────────────────────────────────────

private val InboxBackground = Color(0xFF050505)
private val InboxSurface = Color(0xFF111111)
private val InboxChip = Color(0xFF171717)
private val InboxRed = Color(0xFFE53935)

private enum class MessagesTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    CHATS("Mensajes", Icons.Outlined.ChatBubbleOutline, Icons.Filled.Chat),
    GROUPS("Grupos", Icons.Default.Group, Icons.Default.Group),
    COMMUNITIES("Canales", Icons.Default.Public, Icons.Default.Public),
    VOICE("Voz", Icons.Default.Mic, Icons.Default.Mic),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    navController: NavController,
    sharedText: String? = null,
    sharedUri: String? = null,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    var selectedTab by remember { mutableStateOf(MessagesTab.CHATS) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf(ChatFilter.ALL) }

    Scaffold(
        containerColor = InboxBackground,
        topBar = {
            MessagesTopBar(
                onSettingsClick = {
                    navController.navigate(Screen.ChatSettings.route)
                },
                onCallHistoryClick = {
                    navController.navigate(Screen.CallHistory.route)
                },
                onSearchClick = {
                    navController.navigate(Screen.SearchUsers.route)
                },
                onSwitchAccountClick = {
                    navController.navigate(Screen.AccountSettings.route)
                },
                onFilterMenuClick = { showFilterMenu = true },
                showFilterMenu = showFilterMenu,
                activeFilter = activeFilter,
                onFilterSelected = { filter ->
                    activeFilter = filter
                    showFilterMenu = false
                },
                onFilterMenuDismiss = { showFilterMenu = false },
            )
        },
        floatingActionButton = {
            // FAB para nuevo chat / nueva sala dependiendo del tab activo
            when (selectedTab) {
                MessagesTab.CHATS -> {
                    FloatingActionButton(
                        onClick = { navController.navigate(Screen.SearchUsers.route) },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Nuevo chat", modifier = Modifier.size(34.dp))
                    }
                }
                MessagesTab.GROUPS -> {
                    FloatingActionButton(
                        onClick = { navController.navigate(Screen.CreateGroup.route) },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Crear grupo", modifier = Modifier.size(34.dp))
                    }
                }
                MessagesTab.COMMUNITIES -> {
                    FloatingActionButton(
                        onClick = { navController.navigate(Screen.CreateCommunity.route) },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Crear comunidad", modifier = Modifier.size(34.dp))
                    }
                }
                MessagesTab.VOICE -> {
                    FloatingActionButton(
                        onClick = { navController.navigate(Screen.CreateVoiceRoom.route) },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Crear sala de voz", modifier = Modifier.size(34.dp))
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(InboxBackground),
        ) {
            // ── Tab Row ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InboxSurface)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MessagesTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Surface(
                        onClick = { selectedTab = tab },
                        shape = CircleShape,
                        color = if (selected) Color.White else InboxChip,
                        contentColor = if (selected) Color.Black else Color.White,
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = tab.label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // ── Content ──────────────────────────────────────────────────────
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "messages_tab_content",
            ) { tab ->
                when (tab) {
                    MessagesTab.CHATS -> PrivateChatsTab(
                        onNavigateToSearch = {
                            navController.navigate(Screen.SearchUsers.route)
                        },
                        onNavigateToChat = { chatId ->
                            navController.navigate(Screen.Chat.createRoute(chatId))
                        },
                    )
                    MessagesTab.GROUPS -> GroupsTab(
                        navController = navController,
                        onNavigateToGroupChat = { groupId ->
                            navController.navigate(Screen.GroupChat.createRoute(groupId))
                        },
                    )
                    MessagesTab.COMMUNITIES -> CommunitiesTab(
                        navController = navController,
                        onNavigateToCommunityChat = { communityId ->
                            navController.navigate(Screen.CommunityChat.createRoute(communityId))
                        },
                    )
                    MessagesTab.VOICE -> VoiceRoomsTab(
                        navController = navController,
                        onNavigateToCreateRoom = {
                            navController.navigate(Screen.CreateVoiceRoom.route)
                        },
                        onNavigateToRoomDetail = { roomId ->
                            navController.navigate(Screen.RoomDetail.createRoute(roomId))
                        },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TopAppBar completo — Sprint 1.1
// ─────────────────────────────────────────────────────────────────────────────

/** Filtros de la bandeja de entrada */
enum class ChatFilter(val label: String) {
    ALL("Todos"),
    UNREAD("No leídos"),
    FAVORITES("Favoritos"),
    GROUPS("Grupos"),
    ARCHIVED("Archivados"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesTopBar(
    onSettingsClick: () -> Unit,
    onCallHistoryClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSwitchAccountClick: () -> Unit,
    onFilterMenuClick: () -> Unit,
    showFilterMenu: Boolean,
    activeFilter: ChatFilter,
    onFilterSelected: (ChatFilter) -> Unit,
    onFilterMenuDismiss: () -> Unit,
) {
    Surface(color = InboxSurface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSwitchAccountClick) {
                Icon(
                    imageVector = Icons.Default.ManageAccounts,
                    contentDescription = "Cambiar cuenta",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Bandeja de entrada",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }

            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Buscar",
                    tint = Color(0xFFCED0D7),
                    modifier = Modifier.size(30.dp),
                )
            }

            Box {
                IconButton(onClick = onFilterMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Filtros",
                        tint = Color(0xFFCED0D7),
                        modifier = Modifier.size(30.dp),
                    )
                }
                DropdownMenu(
                    expanded = showFilterMenu,
                    onDismissRequest = onFilterMenuDismiss,
                ) {
                    ChatFilter.entries.forEach { filter ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(
                                        androidx.compose.ui.unit.Dp(8f),
                                    ),
                                ) {
                                    if (filter == activeFilter) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(androidx.compose.ui.unit.Dp(24f)))
                                    }
                                    Text(filter.label)
                                }
                            },
                            onClick = { onFilterSelected(filter) },
                        )
                    }
                    Divider()
                    DropdownMenuItem(
                        text = { Text("Ajustes de mensajes") },
                        onClick = {
                            onFilterMenuDismiss()
                            onSettingsClick()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Registro de llamadas") },
                        onClick = {
                            onFilterMenuDismiss()
                            onCallHistoryClick()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.CallEnd, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}
