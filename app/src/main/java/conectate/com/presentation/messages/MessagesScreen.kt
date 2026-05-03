package conectate.com.presentation.messages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen

// ──────────────────────────────────────────────────────────────────────────────
// MessagesScreen — Sprint 1.1 completo
// Barra superior: Ajustes · Registro de llamadas · Buscar · Cambio de cuenta
//                 Menú (Todos / No leídos / Favoritos / Archivados)
// 4 Tabs: Chats · Grupos · Comunidades · Voz
// ──────────────────────────────────────────────────────────────────────────────

private enum class MessagesTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    CHATS("Chats", Icons.Outlined.ChatBubbleOutline, Icons.Filled.Chat),
    GROUPS("Grupos", Icons.Default.Group, Icons.Default.Group),
    COMMUNITIES("Comunidades", Icons.Default.Public, Icons.Default.Public),
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
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(Icons.Default.EditNote, contentDescription = "Nuevo chat")
                    }
                }
                MessagesTab.GROUPS -> {
                    FloatingActionButton(
                        onClick = { navController.navigate(Screen.CreateGroup.route) },
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = "Crear grupo")
                    }
                }
                MessagesTab.COMMUNITIES -> {
                    FloatingActionButton(
                        onClick = { navController.navigate(Screen.CreateCommunity.route) },
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Crear comunidad")
                    }
                }
                MessagesTab.VOICE -> {
                    FloatingActionButton(
                        onClick = { navController.navigate(Screen.CreateVoiceRoom.route) },
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Crear sala de voz")
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Tab Row ──────────────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = androidx.compose.ui.unit.Dp(0f),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {},
            ) {
                MessagesTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Tab(
                        selected = selected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.icon,
                                contentDescription = tab.label,
                            )
                        },
                        text = { Text(tab.label) },
                    )
                }
            }
                    Divider()

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
    TopAppBar(
        title = {
            Text(
                text = "Mensajes",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        actions = {
            // 1. Ícono de registro de llamadas
            IconButton(onClick = onCallHistoryClick) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "Registro de llamadas",
                )
            }

            // 2. Lupa — búsqueda global
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Buscar",
                )
            }

            // 3. Cambio de cuenta
            IconButton(onClick = onSwitchAccountClick) {
                Icon(
                    imageVector = Icons.Default.ManageAccounts,
                    contentDescription = "Cambiar cuenta",
                )
            }

            // 4. Ajustes de mensajes
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Ajustes",
                )
            }

            // 5. Menú 3 puntos — filtros
            Box {
                IconButton(onClick = onFilterMenuClick) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Más opciones",
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
                        text = { Text("Nueva transmisión") },
                        onClick = onFilterMenuDismiss,
                        leadingIcon = {
                            Icon(Icons.Default.Campaign, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Mensajes de IA") },
                        onClick = onFilterMenuDismiss,
                        leadingIcon = {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
