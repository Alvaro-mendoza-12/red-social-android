package conectate.com.presentation.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.data.localfallback.GuestContent
import conectate.com.data.localfallback.GuestComment
import conectate.com.data.localfallback.GuestIdentity
import conectate.com.data.localfallback.GuestModeApi
import conectate.com.data.localfallback.GuestPendingPreview
import conectate.com.data.localfallback.GuestProfile
import conectate.com.presentation.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class GuestModeViewModel @Inject constructor(private val api: GuestModeApi) : ViewModel() {
    private val _identity = MutableStateFlow<GuestIdentity?>(api.savedIdentity())
    val identity = _identity.asStateFlow()
    private val _videos = MutableStateFlow<List<GuestContent>>(emptyList())
    val videos = _videos.asStateFlow()
    private val _posts = MutableStateFlow<List<GuestContent>>(emptyList())
    val posts = _posts.asStateFlow()
    private val _moments = MutableStateFlow<List<GuestContent>>(emptyList())
    val moments = _moments.asStateFlow()
    private val _profiles = MutableStateFlow<List<GuestProfile>>(emptyList())
    val profiles = _profiles.asStateFlow()
    private val _selectedProfile = MutableStateFlow<GuestProfile?>(null)
    val selectedProfile = _selectedProfile.asStateFlow()
    private val _comments = MutableStateFlow<List<GuestComment>>(emptyList())
    val comments = _comments.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private val _busy = MutableStateFlow(0)
    val busy = _busy.asStateFlow()

    private suspend fun <T> request(block: suspend () -> T): Result<T> {
        _busy.value += 1
        _error.value = null
        return try {
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _error.value = if (error is IllegalStateException) error.message else
                "No pudimos conectar. Revisa tu conexión y vuelve a intentarlo."
            Result.failure(error)
        } finally { _busy.value -= 1 }
    }

    fun hasSavedSession() = api.hasActiveGuest()
    fun rememberPendingPreview(type: String, id: String = "") = api.rememberPendingPreview(type, id)
    fun consumePendingPreview(): GuestPendingPreview? = api.consumePendingPreview()

    private val _previewContent = MutableStateFlow<GuestContent?>(null)
    val previewContent = _previewContent.asStateFlow()

    fun openContent(kind: String, id: String) = viewModelScope.launch {
        request { api.content(kind, id) }
            .onSuccess { _previewContent.value = it }
    }

    fun clearPreviewContent() { _previewContent.value = null }

    fun enter() = viewModelScope.launch {
        request { api.ensureIdentity() }.onSuccess { _identity.value = it }
    }

    fun refresh(section: String) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            when (section) {
                "videos" -> request { api.videos() }.onSuccess { _videos.value = it }
                "social" -> request { api.posts() to api.moments() }.onSuccess {
                    _posts.value = it.first; _moments.value = it.second
                }
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) { _profiles.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            delay(350)
            request { api.searchProfiles(query) }
                .onSuccess { _profiles.value = it; _error.value = null }
        }
    }

    fun openProfile(id: String) = viewModelScope.launch {
        request { api.profile(id) }
            .onSuccess { _selectedProfile.value = it }
    }

    fun closeProfile() { _selectedProfile.value = null }
    fun rememberProfileForSignup() { _selectedProfile.value?.id?.let(api::rememberPendingProfile) }
    fun clearPendingProfile() = api.clearPendingProfile()
    fun consumePendingProfile() = api.consumePendingProfile()
    fun loadComments(kind: String, id: String) = viewModelScope.launch {
        _comments.value = emptyList()
        request { api.comments(kind, id) }
            .onSuccess { _comments.value = it }
    }
    fun leave() { api.clear(); api.clearPendingProfile() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestScreen(navController: NavController, viewModel: GuestModeViewModel = hiltViewModel()) {
    val identity by viewModel.identity.collectAsState()
    val videos by viewModel.videos.collectAsState()
    val posts by viewModel.posts.collectAsState()
    val moments by viewModel.moments.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val previewContent by viewModel.previewContent.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var section by rememberSaveable { mutableStateOf("messages") }
    var query by rememberSaveable { mutableStateOf("") }
    var restrictedAction by remember { mutableStateOf<String?>(null) }
    var playingVideo by remember { mutableStateOf<String?>(null) }
    var previewImage by remember { mutableStateOf<String?>(null) }
    var commentTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) { viewModel.enter() }
    LaunchedEffect(section, identity?.id) { if (identity != null) viewModel.refresh(section) }
    LaunchedEffect(identity?.id) {
        if (identity == null) return@LaunchedEffect
        when (val preview = viewModel.consumePendingPreview()) {
            null -> Unit
            else -> when (preview.type) {
                "profile" -> viewModel.openProfile(preview.id)
                "video" -> { section = "videos"; viewModel.openContent("videos", preview.id) }
                "post" -> { section = "social"; viewModel.openContent("posts", preview.id) }
                "social" -> section = "social"
                "messages" -> section = "messages"
                else -> restrictedAction = preview.type
            }
        }
    }

    fun createAccount() {
        viewModel.rememberProfileForSignup()
        navController.navigate(Screen.Register.route)
    }

    restrictedAction?.let { action ->
        AlertDialog(
            onDismissRequest = { restrictedAction = null; viewModel.clearPendingProfile() },
            title = { Text("Estás explorando como invitado") },
            text = { Text("Crea una cuenta para $action. Podrás seguir explorando si prefieres hacerlo después.") },
            confirmButton = {
                Button(onClick = { restrictedAction = null; createAccount() }) { Text("Crear una cuenta ahora") }
            },
            dismissButton = { TextButton(onClick = {
                restrictedAction = null; viewModel.clearPendingProfile()
            }) { Text("Seguir explorando") } },
        )
    }

    playingVideo?.let { url ->
        AlertDialog(
            onDismissRequest = { playingVideo = null },
            title = { Text("Video público") },
            text = { GuestVideoPlayer(url) },
            confirmButton = { TextButton(onClick = { playingVideo = null }) { Text("Cerrar") } },
        )
    }
    previewImage?.let { url ->
        AlertDialog(
            onDismissRequest = { previewImage = null },
            title = { Text("Momento público") },
            text = { AsyncImage(model = url, contentDescription = "Momento público", modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) },
            confirmButton = { TextButton(onClick = { previewImage = null }) { Text("Cerrar") } },
        )
    }
    previewContent?.let { content ->
        AlertDialog(
            onDismissRequest = viewModel::clearPreviewContent,
            title = { Text(content.userName) },
            text = {
                Column {
                    if (!content.videoUrl.isNullOrBlank()) GuestVideoPlayer(content.videoUrl)
                    else {
                        Text(content.content)
                        content.mediaUrl?.let {
                            if (content.mediaType == "video" || it.substringBefore('?').endsWith(".mp4", true)) {
                                GuestVideoPlayer(it)
                            } else AsyncImage(model = it, contentDescription = "Publicación pública",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
                        }
                    }
                    TextButton(onClick = { viewModel.openProfile(content.userId) }) { Text("Ver perfil") }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::clearPreviewContent) { Text("Cerrar") } },
        )
    }

    commentTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { commentTarget = null },
            title = { Text("Comentarios") },
            text = {
                Column {
                    if (busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.loadComments(target.first, target.second) }) { Text("Reintentar") }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        if (comments.isEmpty() && busy == 0 && error == null) item { Text("Aún no hay comentarios.") }
                        items(comments.take(100), key = { it.id }) { comment ->
                            Text("${comment.userName}: ${comment.content}", modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                    TextButton(onClick = { restrictedAction = "comentar" }) { Text("Escribir comentario") }
                }
            },
            confirmButton = { TextButton(onClick = { commentTarget = null }) { Text("Cerrar") } },
        )
    }

    selectedProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = viewModel::closeProfile,
            title = { Text(profile.displayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("@${profile.username ?: "usuario"}")
                    profile.bio?.let { Text(it) }
                    OutlinedButton(onClick = {
                        viewModel.rememberProfileForSignup(); viewModel.closeProfile(); restrictedAction = "seguir personas"
                    }) { Text("Seguir") }
                    OutlinedButton(onClick = {
                        viewModel.rememberProfileForSignup(); viewModel.closeProfile(); restrictedAction = "enviar mensajes"
                    }) { Text("Enviar mensaje") }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeProfile) { Text("Cerrar") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Explorar Novvin") },
                actions = { TextButton(onClick = ::createAccount) { Text("Crear cuenta") } },
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("messages", "Mensajes", Icons.Default.ChatBubbleOutline),
                    Triple("videos", "Videos", Icons.Default.VideoLibrary),
                    Triple("social", "Social", Icons.Default.Public),
                    Triple("search", "Buscar", Icons.Default.Search),
                    Triple("profile", "Perfil", Icons.Default.Person),
                ).forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = section == route,
                        onClick = { section = route },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (identity == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (busy > 0) CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(error ?: "Preparando tu visita…")
                        if (error != null) TextButton(onClick = viewModel::enter) { Text("Reintentar") }
                    }
                }
            } else {
                if (busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = {
                        if (section == "messages" || section == "search") viewModel.search(query)
                        else viewModel.refresh(section)
                    }) { Text("Reintentar") }
                }
                when (section) {
                    "videos" -> if (busy == 0 || videos.isNotEmpty()) GuestContentList(
                        videos, "Aún no hay videos públicos",
                        onOpen = { video -> if (!video.videoUrl.isNullOrBlank()) playingVideo = video.videoUrl },
                        onProfile = { viewModel.openProfile(it.userId) },
                        onComments = { video -> commentTarget = "videos" to video.id; viewModel.loadComments("videos", video.id) },
                        onRestricted = { restrictedAction = it },
                    )
                    "social" -> {
                        if (moments.isNotEmpty()) {
                            Text("Momentos públicos", style = MaterialTheme.typography.titleMedium)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(moments, key = { it.id }) { moment ->
                                    ElevatedCard(onClick = {
                                        if (moment.mediaType == "video") playingVideo = moment.mediaUrl
                                        else previewImage = moment.mediaUrl
                                    }, modifier = Modifier.width(110.dp)) {
                                        Column(Modifier.padding(8.dp)) {
                                            AsyncImage(model = moment.thumbnailUrl ?: moment.mediaUrl,
                                                contentDescription = "Momento público",
                                                modifier = Modifier.fillMaxWidth().height(90.dp))
                                            Text(moment.caption.ifBlank { "Ver momento" }, maxLines = 1)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (busy == 0 || posts.isNotEmpty()) GuestContentList(
                            posts, "Aún no hay publicaciones públicas",
                            onOpen = { viewModel.openContent("posts", it.id) },
                            onProfile = { viewModel.openProfile(it.userId) },
                            onComments = { post -> commentTarget = "posts" to post.id; viewModel.loadComments("posts", post.id) },
                            onRestricted = { restrictedAction = it },
                        )
                    }
                    "messages", "search" -> {
                        Text(
                            if (section == "messages") "Descubre personas. Para conversar, crea una cuenta." else "Busca personas por nombre o @usuario.",
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; viewModel.search(it) },
                            label = { Text("Buscar personas") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        LazyColumn {
                            if (query.trim().length >= 2 && profiles.isEmpty() && busy == 0 && error == null) {
                                item { Text("No encontramos personas. Prueba otro nombre o @usuario.", modifier = Modifier.padding(vertical = 16.dp)) }
                            }
                            items(profiles, key = { it.id }) { profile ->
                                ListItem(
                                    headlineContent = { Text("@${profile.username ?: "usuario"}") },
                                    supportingContent = { Text(profile.displayName) },
                                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                                    modifier = Modifier.clickable { viewModel.openProfile(profile.id) },
                                )
                            }
                        }
                    }
                    "profile" -> {
                        Spacer(Modifier.height(24.dp))
                        Icon(Icons.Default.Person, contentDescription = "Avatar de invitado", modifier = Modifier.size(64.dp))
                        Text("Modo invitado", color = MaterialTheme.colorScheme.primary)
                        Text(identity!!.displayName, style = MaterialTheme.typography.headlineMedium)
                        Text("@${identity!!.username}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("Estás explorando como invitado. Esta identidad temporal no publica ni envía mensajes.")
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = ::createAccount, modifier = Modifier.fillMaxWidth()) { Text("Crear mi cuenta") }
                        TextButton(onClick = {
                            viewModel.leave()
                            navController.navigate(Screen.Login.route) {
                                popUpTo(Screen.Guest.route) { inclusive = true }
                            }
                        }) { Text("Salir del modo invitado") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuestContentList(
    contents: List<GuestContent>, emptyMessage: String,
    onOpen: (GuestContent) -> Unit,
    onProfile: (GuestContent) -> Unit,
    onComments: (GuestContent) -> Unit,
    onRestricted: (String) -> Unit,
) {
    if (contents.isEmpty()) {
        Text(emptyMessage, modifier = Modifier.padding(vertical = 24.dp))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(contents, key = { it.id }) { content ->
            ElevatedCard(onClick = { onOpen(content) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    TextButton(onClick = { onProfile(content) }) { Text(content.userName) }
                    val image = content.thumbnailUrl ?: content.coverUrl ?: content.mediaUrl
                    val mediaIsVideo = content.mediaType == "video" ||
                        (content.videoUrl == null && image?.substringBefore('?')?.endsWith(".mp4", true) == true)
                    if (!image.isNullOrBlank() && !mediaIsVideo) {
                        AsyncImage(model = image, contentDescription = "Vista previa pública", modifier = Modifier.fillMaxWidth().height(180.dp))
                    } else if (mediaIsVideo) {
                        Icon(Icons.Default.PlayCircle, contentDescription = "Video público",
                            modifier = Modifier.fillMaxWidth().height(96.dp))
                    }
                    val description = content.content.ifBlank { content.description.ifBlank { content.title } }
                    if (description.isNotBlank()) Text(description, maxLines = 4)
                    if (!content.videoUrl.isNullOrBlank()) Text("Toca para ver", color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onComments(content) }) { Text("Comentarios") }
                        TextButton(onClick = { onRestricted("dar Me gusta") }) { Text("Me gusta") }
                    }
                    TextButton(onClick = { onRestricted("compartir contenido") }) { Text("Compartir") }
                }
            }
        }
    }
}

@Composable
private fun GuestVideoPlayer(url: String) {
    val context = LocalContext.current
    val playerHeight = (LocalConfiguration.current.screenHeightDp - 220).coerceIn(140, 420).dp
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().height(playerHeight),
    )
}
