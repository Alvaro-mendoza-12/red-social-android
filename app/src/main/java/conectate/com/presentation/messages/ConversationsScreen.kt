package conectate.com.presentation.messages

import android.net.Uri
import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen
import conectate.com.R

// Colors now resolved via MaterialTheme.colorScheme for light/dark compatibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    navController: NavController,
    viewModel: MessagingViewModel = hiltViewModel(),
    stickerImportViewModel: StickerPickerViewModel = hiltViewModel(),
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
    val unreadDmsCount by viewModel.unreadDmsCount.collectAsState()
    val unreadGroupsCount by viewModel.unreadGroupsCount.collectAsState()
    val unreadCommunitiesCount by viewModel.unreadCommunitiesCount.collectAsState()
    
    val tabs = listOf("Mensajes", "Grupos", "Comunidades", "Voz")
    val tabBadges = listOf(unreadDmsCount, unreadGroupsCount, unreadCommunitiesCount, 0)
    val stickerImportMessage by stickerImportViewModel.actionMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var stickerImportHandled by rememberSaveable(sharedStickerUri) { mutableStateOf(false) }

    LaunchedEffect(sharedStickerUri) {
        if (!stickerImportHandled && !sharedStickerUri.isNullOrBlank()) {
            stickerImportHandled = true
            stickerImportViewModel.createStickerFromImage(Uri.parse(sharedStickerUri), preferredPackId = null)
        }
    }

    LaunchedEffect(stickerImportMessage) {
        stickerImportMessage?.let {
            snackbarHostState.showSnackbar(it)
            stickerImportViewModel.clearActionMessage()
        }
    }

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

    val baseDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, fontScale = 1f)
    ) {
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
                            .height(56.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { navController.navigate(Screen.Settings.route) },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ManageAccounts,
                                contentDescription = "Cambiar cuenta",
                                tint = colorScheme.onSurface,
                                modifier = Modifier.size(25.dp)
                            )
                        }

                        Text(
                            text = "Bandeja de entrada",
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 17.sp,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onNavigateToSearch,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Buscar",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            IconButton(
                                onClick = { navController.navigate(Screen.ChatSettings.route) },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Filtros",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 17.dp, end = 17.dp, top = 2.dp, bottom = 15.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tabs.forEachIndexed { index, title ->
                            val selected = selectedTab == index
                            Surface(
                                onClick = { viewModel.onTabSelected(index) },
                                shape = CircleShape,
                                color = if (selected) colorScheme.primary else colorScheme.surfaceVariant,
                                contentColor = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val count = tabBadges.getOrNull(index) ?: 0
                                    Text(
                                        text = title,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = if (title.length > 9) 11.sp else 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(
                                            start = 2.dp,
                                            end = if (count > 0) 18.dp else 2.dp
                                        )
                                    )
                                    if (count > 0) {
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = (-4).dp, y = (-5).dp)
                                                .height(18.dp)
                                                .widthIn(min = 18.dp),
                                            shape = CircleShape,
                                            color = Color(0xFFE53935),
                                            contentColor = Color.White
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(horizontal = 5.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (count > 99) "99+" else count.toString(),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1
                                                )
                                            }
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
}

