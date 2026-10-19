package conectate.com.presentation.messages

import android.net.Uri
import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen
import conectate.com.R
import kotlinx.coroutines.launch

// Colors now resolved via MaterialTheme.colorScheme for light/dark compatibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    navController: NavController,
    viewModel: MessagingViewModel = conectate.com.core.observability.startupViewModel("messaging model"),
    stickerImportViewModel: StickerPickerViewModel? = null,
    sharedStickerUri: String? = null,
    onNavigateToChat: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToCallHistory: () -> Unit,
    onNavigateToCreateGroup: () -> Unit,
    onNavigateToCreateCommunity: () -> Unit,
    onNavigateToCreateVoiceRoom: () -> Unit,
    onNavigateToNovvin: () -> Unit,
    onNavigateToGroupChat: (String) -> Unit,
    onNavigateToCommunityChat: (String) -> Unit,
    onNavigateToRoomDetail: (String) -> Unit
) {
    val selectedTab by viewModel.selectedTabIndex.collectAsState()
    val accountsViewModel: MessageAccountsViewModel = conectate.com.core.observability.startupViewModel("message accounts model")
    val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()
    val accountSwitch by accountsViewModel.switching.collectAsStateWithLifecycle()
    val accountError by accountsViewModel.error.collectAsStateWithLifecycle()
    val switchCompleted by accountsViewModel.completed.collectAsStateWithLifecycle()
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    var showSavedMessages by rememberSaveable { mutableStateOf(false) }
    val activeAccountName = accounts.firstOrNull { it.isActive }?.account?.let { it.display_name ?: it.email }
    LaunchedEffect(switchCompleted) { if (switchCompleted > 0) showAccounts = false }
    if (showAccounts) {
        MessageAccountsDialog(
            accounts, accountSwitch.isSwitching, accountError,
            onSwitch = accountsViewModel::switchAccount,
            onManage = { showAccounts = false; navController.navigate(Screen.Settings.route) },
            onDismiss = { showAccounts = false }
        )
    }
    if (showSavedMessages) {
        GlobalSavedMessagesSheet(onDismiss = { showSavedMessages = false },
            onOpenConversation = { kind, roomId, messageId ->
                when (kind) {
                    "chat" -> navController.navigate(Screen.Chat.createRoute(roomId, focusMessageId = messageId))
                    "group" -> navController.navigate(Screen.GroupChat.createRoute(roomId, focusMessageId = messageId))
                    "community" -> navController.navigate(Screen.CommunityChat.createRoute(roomId, focusMessageId = messageId))
                }
            })
    }
    val unreadDmsCount by viewModel.unreadDmsCount.collectAsState()
    val unreadGroupsCount by viewModel.unreadGroupsCount.collectAsState()
    val unreadCommunitiesCount by viewModel.unreadCommunitiesCount.collectAsState()
    
    val tabs = listOf("Mensajes", "Grupos", "Comunidades", "Voz")
    val tabBadges = listOf(unreadDmsCount, unreadGroupsCount, unreadCommunitiesCount, 0)
    // Creating stickers is only needed when an image was shared into this screen.
    val importViewModel = stickerImportViewModel ?: if (!sharedStickerUri.isNullOrBlank()) {
        conectate.com.core.observability.startupViewModel<StickerPickerViewModel>("sticker import model")
    } else null
    val stickerImportMessage = importViewModel?.actionMessage?.collectAsState()?.value
    val snackbarHostState = remember { SnackbarHostState() }
    val navigationScope = rememberCoroutineScope()
    var stickerImportHandled by rememberSaveable(sharedStickerUri) { mutableStateOf(false) }

    LaunchedEffect(sharedStickerUri) {
        if (!stickerImportHandled && !sharedStickerUri.isNullOrBlank()) {
            stickerImportHandled = true
            importViewModel?.createStickerFromImage(Uri.parse(sharedStickerUri), preferredPackId = null)
        }
    }

    LaunchedEffect(stickerImportMessage) {
        stickerImportMessage?.let {
            snackbarHostState.showSnackbar(it)
            importViewModel?.clearActionMessage()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        viewModel.onScreenVisibilityChanged(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onScreenVisibilityChanged(true)
                Lifecycle.Event.ON_STOP -> viewModel.onScreenVisibilityChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenVisibilityChanged(false)
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    Scaffold(
            containerColor = colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                conectate.com.presentation.common.KeyboardSafeSnackbarHost(snackbarHostState)
            },
            topBar = {
                Column(modifier = Modifier.background(colorScheme.surface)) {
                    Row(
                        modifier = Modifier
                            .statusBarsPadding()
                            .height(52.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { accountsViewModel.clearError(); showAccounts = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ManageAccounts,
                                contentDescription = "Cambiar cuenta",
                                tint = colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Bandeja de entrada", fontWeight = FontWeight.Bold,
                                fontSize = 18.sp, color = colorScheme.onSurface, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                            )
                            if (activeAccountName != null) Text(
                                text = "Activa: $activeAccountName", fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MessageWebScannerButton(navController = navController, onError = {
                                navigationScope.launch {
                                    snackbarHostState.showSnackbar("No se pudo abrir el escáner. Vuelve a intentarlo.")
                                }
                            })
                            IconButton(
                                onClick = onNavigateToSearch,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Buscar",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(
                                onClick = { navController.navigate(Screen.ChatSettings.route) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Filtros",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tabs.forEachIndexed { index, title ->
                            val selected = selectedTab == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .selectable(
                                        selected = selected,
                                        onClick = { viewModel.onTabSelected(index) },
                                        role = Role.Tab
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                val count = tabBadges.getOrNull(index) ?: 0
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = title,
                                        color = if (selected) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                                        fontSize = if (title.length > 9) 11.sp else 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    InboxUnreadBadge(count)
                                }
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth(0.66f)
                                            .height(2.dp)
                                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                            .background(colorScheme.primary)
                                    )
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

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FloatingActionButton(
                        onClick = onNavigateToNovvin,
                        modifier = Modifier.size(50.dp),
                        containerColor = colorScheme.tertiaryContainer,
                        contentColor = colorScheme.onTertiaryContainer,
                        shape = CircleShape
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_novvin),
                            contentDescription = "Abrir Novvin",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    FloatingActionButton(
                        onClick = fabAction,
                        modifier = Modifier.size(56.dp),
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = fabDescription,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .padding(padding)
            ) {
                when (selectedTab) {
                    0 -> PrivateChatsTab(
                        onNavigateToSearch = onNavigateToSearch,
                        onNavigateToCallHistory = onNavigateToCallHistory,
                        onOpenSavedMessages = { showSavedMessages = true },
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

