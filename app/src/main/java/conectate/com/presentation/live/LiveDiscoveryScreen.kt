package conectate.com.presentation.live

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.data.model.LiveStream
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.common.AppFeedbackBus
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveDiscoveryScreen(
    navController: NavController,
    viewModel: LiveViewModel = hiltViewModel()
) {
    val activeStreams by viewModel.activeStreams.collectAsState()
    val replays by viewModel.liveReplays.collectAsStateWithLifecycle()
    val myHistory by viewModel.myLiveHistory.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.replayUploadProgress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isLoadingStreams by viewModel.isLoadingStreams.collectAsStateWithLifecycle()
    val streamsLoadError by viewModel.streamsLoadError.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    var pendingReplayLiveId by remember { mutableStateOf<String?>(null) }
    val replayPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val liveId = pendingReplayLiveId
        pendingReplayLiveId = null
        if (uri != null && liveId != null) viewModel.publishReplay(liveId, uri)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshStreams()
    }
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        AppFeedbackBus.showError(message)
        viewModel.clearError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transmisiones en Vivo", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshStreams, enabled = !isLoadingStreams) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar directos")
                    }
                }
            )
        }
    ) { padding ->
        val visibleItems = when (selectedTab) {
            1 -> replays
            2 -> myHistory
            else -> activeStreams
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                listOf("En vivo", "Repeticiones", "Mis directos").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, maxLines = 1) }
                    )
                }
            }
            if (selectedTab == 0 && isLoadingStreams && activeStreams.isNotEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (selectedTab == 0 && streamsLoadError != null && activeStreams.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "La lista puede estar desactualizada.",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = viewModel::refreshStreams) { Text("Reintentar") }
                }
            }
            if (uploadProgress > 0f) {
                LinearProgressIndicator(
                    progress = { uploadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        if (visibleItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (selectedTab == 0 && isLoadingStreams) {
                        CircularProgressIndicator()
                    } else {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when (selectedTab) {
                            1 -> "Todavía no hay repeticiones publicadas"
                            2 -> "Aún no has finalizado ningún directo"
                            else -> when {
                                isLoadingStreams -> "Buscando directos…"
                                streamsLoadError != null -> streamsLoadError.orEmpty()
                                else -> "No hay transmisiones activas"
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    if (selectedTab == 0 && streamsLoadError != null && !isLoadingStreams) {
                        TextButton(onClick = viewModel::refreshStreams) { Text("Reintentar") }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(156.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
            ) {
                items(visibleItems, key = { it.id }) { stream ->
                    LiveStreamItem(
                        stream = stream,
                        badgeText = when {
                            selectedTab == 0 -> "LIVE"
                            !stream.replayUrl.isNullOrBlank() -> "REPETICIÓN"
                            else -> "PUBLICAR"
                        },
                        onClick = {
                            when {
                                selectedTab == 0 ->
                                    navController.navigate(Screen.LiveViewer.createRoute(stream.id))
                                !stream.replayUrl.isNullOrBlank() ->
                                    navController.navigate(Screen.LiveReplay.createRoute(stream.id))
                                selectedTab == 2 -> {
                                    pendingReplayLiveId = stream.id
                                    replayPicker.launch("video/mp4")
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
fun LiveStreamItem(
    stream: LiveStream,
    badgeText: String = "LIVE",
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.8f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Preview (using host avatar or a placeholder)
            AsyncImage(
                model = stream.hostAvatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            
            // Gradient Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            )

            // Estado del directo o de la repetición
            Surface(
                color = if (badgeText == "LIVE") Color.Red else Color(0xFF5B4BE8),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopStart)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (badgeText == "LIVE") Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopEnd)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stream.viewerCount.toString(),
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }

            // Bottom Info
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = stream.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = stream.hostAvatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stream.hostName,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
