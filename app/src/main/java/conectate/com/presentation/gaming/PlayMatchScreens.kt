@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package conectate.com.presentation.gaming

import android.util.Log
import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import conectate.com.BuildConfig
import conectate.com.R
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import conectate.com.data.model.*
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.FullScreenImageDialog
import conectate.com.presentation.components.MediaItem
import conectate.com.presentation.components.LocalProfileIdentityAccount
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Message

private val GamingStart = PlayMatchColors.Primary
private val GamingEnd = PlayMatchColors.Accent

private enum class GamesSection(val label: String) {
    Home("Inicio"), Catalog("Catálogo"), Players("Jugadores"), Matches("Partidas"), Activity("Actividad"),
}

private enum class GamesLibrary(val label: String) {
    All("Explorar"), Owned("Mis juegos"), Saved("Guardados"),
}

@Composable
fun GamesHomeScreen(
    onOpenProfile: () -> Unit,
    onOpenAddGame: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    onMatchFound: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: PlayMatchViewModel = hiltViewModel(),
    onInboxVisible: (List<PlayMatchConnection>, List<PlayMatchMatch>) -> Unit = { _, _ -> },
) {
    PlayMatchSystemBars()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account = LocalProfileIdentityAccount.current ?: state.profile?.userId
    val context = LocalContext.current
    val library = remember(context, account) { account?.let { PlayMatchSavedGames(context, it) } }
    var savedGames by remember(library) { mutableStateOf(library?.read().orEmpty()) }
    var section by rememberSaveable(account) { mutableStateOf(GamesSection.Home) }
    var collection by rememberSaveable(account) { mutableStateOf(GamesLibrary.All) }
    var openedMatchId by rememberSaveable(account) { mutableStateOf<String?>(null) }
    val lists = rememberSaveableStateHolder()
    val activity = remember(state.connections, state.matches) { playMatchActivity(state.connections, state.matches) }
    val readiness = playMatchStart(state.profile, state.isLoading || state.isHomeProfileLoading)
    val latestInboxCallback by rememberUpdatedState(onInboxVisible)
    val latestOpenMatch by rememberUpdatedState(onMatchFound)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState == Lifecycle.State.RESUMED
    LaunchedEffect(section, resumed, state.capabilities.enabled, state.connections, state.matches) {
        if (section == GamesSection.Activity && resumed && state.capabilities.enabled) {
            latestInboxCallback(state.connections, state.matches)
        }
    }
    LaunchedEffect(resumed, state.session?.status, state.session?.id) {
        val session = state.session
        if (resumed && session?.status == "MATCH_FOUND" && openedMatchId != session.id) {
            openedMatchId = session.id
            latestOpenMatch(session.id)
        }
    }
    LaunchedEffect(section, collection, state.capabilities.enabled) {
        if (section == GamesSection.Catalog && collection == GamesLibrary.All && state.capabilities.enabled) {
            viewModel.prepareCatalogSearch()
        }
        if (section == GamesSection.Players && state.capabilities.enabled) viewModel.preparePlayerSearch()
    }
    PlayMatchNoticeHost(state, viewModel::clearNotice)
    var openBoardGames by rememberSaveable(account) { mutableStateOf(false) }
    var resumeBoardRoom by rememberSaveable(account) { mutableStateOf<String?>(null) }
    if (openBoardGames) {
        BoardGamesScreen(
            onBack = { resumeBoardRoom = null; openBoardGames = false },
            request = viewModel::boardRequest,
            resumeRoomId = resumeBoardRoom,
            onOpenOpponent = { roomId, userId, openChat ->
                resumeBoardRoom = roomId
                if (openChat) onOpenChat(userId) else onOpenPlayer(userId)
            },
        )
        return
    }

    fun selectGame(gameId: String) {
        section = GamesSection.Players
        viewModel.search(query = "", gameId = gameId, platform = null)
    }

    Column(Modifier.fillMaxSize().background(PlayMatchColors.Background).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = PlayMatchDimensions.ScreenPadding, vertical = 10.dp)) {
            PlayMatchHeader(state.profile?.avatarUrl, state.profile?.avatarMediaType, onOpenProfile)
        }
        Card(
            onClick = { openBoardGames = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = PlayMatchDimensions.ScreenPadding, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = PlayMatchColors.Card),
            border = BorderStroke(1.dp, PlayMatchColors.Accent.copy(alpha = .45f)),
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SportsEsports, null, tint = PlayMatchColors.Accent, modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text("Juegos de mesa", color = PlayMatchColors.White, style = MaterialTheme.typography.titleMedium)
                    Text("Juega con amigos, en línea o contra Novvin", color = PlayMatchColors.SecondaryText,
                        style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = PlayMatchColors.Accent)
            }
        }
        if (!state.capabilities.enabled) {
            Box(Modifier.padding(PlayMatchDimensions.ScreenPadding)) {
                when {
                    state.isLoading -> CenteredProgress("Cargando Juegos…")
                    state.error != null -> StatusCard(Icons.Default.Refresh, "No se pudo abrir Juegos",
                        state.error.orEmpty(), "Reintentar", viewModel::refreshHome)
                    else -> ClosedTesterAccessCard(Icons.Default.SportsEsports, "Juegos está en prueba cerrada",
                        "Esta cuenta todavía no tiene acceso a PlayMatch.", "Volver a comprobar", viewModel::refreshHome)
                }
            }
            return
        }
        PrimaryScrollableTabRow(
            selectedTabIndex = section.ordinal,
            edgePadding = 12.dp,
            containerColor = PlayMatchColors.Background,
            contentColor = PlayMatchColors.White,
        ) {
            for (destination in GamesSection.entries) {
                Tab(
                    selected = section == destination,
                    onClick = { section = destination },
                    text = {
                        if (destination == GamesSection.Activity && activity.pending > 0) {
                            Text("${destination.label} (${activity.pending})", maxLines = 1)
                        } else Text(destination.label, maxLines = 1)
                    },
                    selectedContentColor = PlayMatchColors.Accent,
                    unselectedContentColor = PlayMatchColors.SecondaryText,
                )
            }
        }
        lists.SaveableStateProvider(section.name) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = PlayMatchDimensions.ScreenPadding, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (section) {
                    GamesSection.Home -> {
                        item("home_feature") {
                            PlayMatchHomeHero(state.catalog.firstOrNull(), state.session?.status == "SEARCHING",
                                onMatch = { section = GamesSection.Matches }, onDiscover = { section = GamesSection.Players })
                        }
                        item("home_shortcuts") {
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                GamesShortcut("Mis juegos", Icons.Default.SportsEsports, Modifier.weight(1f)) {
                                    collection = GamesLibrary.Owned
                                    section = GamesSection.Catalog
                                }
                                GamesShortcut("Guardados", Icons.Default.BookmarkBorder, Modifier.weight(1f)) {
                                    collection = GamesLibrary.Saved
                                    section = GamesSection.Catalog
                                }
                            }
                        }
                        item("home_popular_title") { SectionTitle("Juegos populares", "Ver catálogo") {
                            collection = GamesLibrary.All
                            section = GamesSection.Catalog
                        } }
                        item("home_popular_list") {
                            when {
                                state.catalog.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(state.catalog.take(8), key = { it.id }) { game -> PopularGameCard(game) { selectGame(game.id) } }
                                }
                                state.isHomeCatalogLoading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = PlayMatchColors.Accent)
                                state.homeCatalogError != null -> StatusCard(Icons.Default.Refresh, "Catálogo sin actualizar",
                                    state.homeCatalogError.orEmpty(), "Reintentar", viewModel::refreshHome)
                                else -> EmptyInline("Por ahora no hay juegos destacados. Puedes explorar el catálogo.")
                            }
                        }
                        item("home_profile") { GamesProfileOverview(state.profile, readiness, onOpenProfile) }
                        if (activity.pending > 0) item("home_activity") {
                            GamesShortcut("${activity.pending} por revisar", Icons.Default.Notifications, Modifier.fillMaxWidth()) {
                                section = GamesSection.Activity
                            }
                        }
                    }
                    GamesSection.Catalog -> {
                        item("library_navigation") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(GamesLibrary.entries, key = { it.name }) { destination ->
                                    PlayMatchFilterChip(destination.label, collection == destination) { collection = destination }
                                }
                            }
                        }
                        when (collection) {
                            GamesLibrary.All -> {
                                item("catalog_search") {
                                    GamesSearchField(state.catalogQuery, viewModel::updateCatalogQuery, "Buscar en el catálogo", viewModel::retryCatalogSearch)
                                }
                                if (state.isCatalogLoading) item("catalog_loading") { CenteredProgress("Buscando juegos…") }
                                state.catalogError?.let { message -> item("catalog_error") {
                                    StatusCard(Icons.Default.Refresh, "No pudimos cargar el catálogo", message, "Reintentar") {
                                        if (state.catalogNextCursor != null) viewModel.loadMoreCatalog() else viewModel.retryCatalogSearch()
                                    }
                                } }
                                if (!state.isCatalogLoading && state.catalogError == null && state.catalogResults.isEmpty()) {
                                    item("catalog_empty") { EmptyInline("No hay juegos para esta búsqueda. Prueba con otro nombre.") }
                                }
                                items(state.catalogResults, key = { "catalog:${it.id}" }) { game ->
                                    GamesCatalogRow(game, savedGames.any { it.id == game.id }, library != null,
                                        onPlayers = { selectGame(game.id) }, onSave = { library?.let { savedGames = it.toggle(game, savedGames) } })
                                }
                                if (state.catalogNextCursor != null) item("catalog_more") {
                                    OutlinedButton(onClick = viewModel::loadMoreCatalog, modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.isCatalogLoadingMore && !state.isCatalogLoading) {
                                        Text(if (state.isCatalogLoadingMore) "Cargando…" else "Ver más juegos", color = PlayMatchColors.White)
                                    }
                                }
                            }
                            GamesLibrary.Owned -> {
                                item("owned_title") { SectionTitle("Mis juegos", "Añadir juego", onOpenAddGame) }
                                if (state.isHomeProfileLoading) item("owned_loading") { CenteredProgress("Cargando tus juegos…") }
                                val owned = state.profile?.games.orEmpty()
                                if (owned.isEmpty() && !state.isHomeProfileLoading) item("owned_empty") {
                                    EmptyInline("Añade un juego y su plataforma para configurar con quién quieres jugar.")
                                }
                                items(owned, key = { "owned:${it.id}" }) { game ->
                                    GamesLibraryRow(game.gameName, game.platform, onOpen = { selectGame(game.gameId) })
                                }
                                item("owned_profile") { TextButton(onOpenProfile) { Text("Editar perfil gamer", color = PlayMatchColors.Accent) } }
                            }
                            GamesLibrary.Saved -> {
                                item("saved_title") { SectionTitle("Guardados", "Ver catálogo") { collection = GamesLibrary.All } }
                                if (savedGames.isEmpty()) item("saved_empty") {
                                    EmptyInline("Guarda juegos del catálogo para volver a buscarlos en este dispositivo.")
                                }
                                items(savedGames, key = { "saved:${it.id}" }) { game ->
                                    GamesLibraryRow(game.name, game.platforms.joinToString(" · "), onOpen = { selectGame(game.id) },
                                        onRemove = { library?.let { savedGames = it.remove(game.id, savedGames) } })
                                }
                            }
                        }
                    }
                    GamesSection.Players -> gamesPlayerItems(state, viewModel, onOpenPlayer, onOpenChat)
                    GamesSection.Matches -> {
                        item("match_control") {
                            GamesMatchPanel(state, readiness, onOpenProfile, onOpenAddGame,
                                onResume = { state.session?.id?.let(onMatchFound) },
                                onStart = { viewModel.startMatchmaking(criteriaFrom(state)) },
                                onCancel = { viewModel.cancelSession() })
                        }
                        if (activity.decisions.isNotEmpty()) item("match_decisions") {
                            GamesShortcut("${activity.decisions.size} coincidencias por decidir", Icons.Default.Notifications, Modifier.fillMaxWidth()) {
                                section = GamesSection.Activity
                            }
                        }
                    }
                    GamesSection.Activity -> gamesActivityItems(activity, state.isWorking, viewModel, onOpenPlayer, onOpenChat)
                }
            }
        }
    }
}

private fun LazyListScope.gamesPlayerItems(
    state: PlayMatchUiState,
    model: PlayMatchViewModel,
    onOpenPlayer: (String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    item("players_search") { GamesSearchField(state.query, model::setQuery, "Buscar jugadores", { model.search() }) }
    item("players_filters") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val gameMenu = (state.catalog + state.catalogResults).distinctBy { it.id }
            FilterRail(gameMenu, state.gameFilter, state.platformFilter,
                onGame = { model.search(gameId = it) }, onPlatform = { model.search(platform = it) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PlayMatchFilterChip("Disponibles ahora", state.availableNowOnly) { model.setAvailableNow(!state.availableNowOnly) }
                TextButton(onClick = model::clearPlayerFilters) { Text("Limpiar filtros", color = PlayMatchColors.Accent) }
            }
        }
    }
    item("players_title") { SectionTitle(if (state.nextCursor == null) "Jugadores (${state.players.size})" else "Jugadores") }
    val searchError = state.playerSearchError
    if (searchError != null && state.players.isEmpty()) item("players_error") {
        StatusCard(Icons.Default.Refresh, "No se pudieron cargar los jugadores", searchError, "Reintentar", { model.search() })
    } else if ((state.isSearchingPlayers || !state.hasSearchedPlayers) && state.players.isEmpty()) {
        item("players_loading") { CenteredProgress("Buscando jugadores…") }
    } else if (state.players.isEmpty()) {
        item("players_empty") { EmptyInline("No encontramos jugadores con estos filtros. Cambia el juego, la plataforma o la disponibilidad.") }
    }
    val byPlayer = state.connections.filter { it.otherUserId.isNotBlank() }.associateBy { it.otherUserId }
    items(state.players, key = { "players:${it.userId}" }) { player ->
        val relation = byPlayer[player.userId]
        val canChat = player.connected || relation?.status == "ACCEPTED"
        val pending = relation?.status == "PENDING"
        PlayerRecommendationCard(player, onOpenPlayer,
            action = if (canChat) "Abrir chat" else if (pending) "Pendiente" else "Conectar",
            enabled = !pending && !state.isWorking,
            onConnect = { if (canChat) onOpenChat(player.userId) else model.connect(player.userId) })
    }
    if (state.nextCursor != null) item("players_more") {
        if (searchError != null) Text(searchError, color = PlayMatchColors.SecondaryText)
        OutlinedButton(onClick = model::loadMore, enabled = !state.isSearchingPlayers, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (state.isSearchingPlayers) "Cargando…" else if (searchError != null) "Reintentar" else "Ver más jugadores", color = PlayMatchColors.White)
        }
    }
}

private fun LazyListScope.gamesActivityItems(
    activity: PlayMatchActivity,
    working: Boolean,
    model: PlayMatchViewModel,
    onOpenPlayer: (String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    item("activity_title") { SectionTitle("Actividad de PlayMatch", "Actualizar", model::refreshHome) }
    if (activity.isEmpty) item("activity_empty") { EmptyInline("Tus solicitudes, coincidencias y conversaciones aparecerán aquí.") }
    if (activity.requests.isNotEmpty()) item("incoming_title") { SectionTitle("Solicitudes recibidas (${activity.requests.size})") }
    items(activity.requests, key = { "request:${it.id}" }) { connection ->
        ConnectionRequestCard(connection, onAccept = { model.decideConnection(connection.id, "ACCEPT") },
            onReject = { model.decideConnection(connection.id, "REJECT") }, enabled = !working)
    }
    if (activity.decisions.isNotEmpty()) item("decision_title") { SectionTitle("Coincidencias por decidir (${activity.decisions.size})") }
    items(activity.decisions, key = { "decision:${it.id}" }) { match ->
        IncomingMatchCard(match, onAccept = { model.decideMatchById(match.id, "ACCEPT") },
            onSkip = { model.decideMatchById(match.id, "SKIP") }, onOpen = { match.player?.userId?.let(onOpenPlayer) }, enabled = !working)
    }
    if (activity.sent.isNotEmpty()) item("sent_title") { SectionTitle("Solicitudes enviadas") }
    items(activity.sent, key = { "sent:${it.id}" }) { connection ->
        GamesLibraryRow(connection.otherName, "Pendiente de respuesta", onOpen = connection.otherUserId
            .takeIf(String::isNotBlank)?.let { userId -> { onOpenPlayer(userId) } })
    }
    if (activity.conversations.isNotEmpty()) item("conversations_title") { SectionTitle("Conversaciones de juego") }
    items(activity.conversations, key = { "conversation:${it.id}" }) { connection ->
        GamesLibraryRow(connection.otherName, "Abrir conversación", onOpen = { onOpenChat(connection.otherUserId) })
    }
}

@Composable
fun GamingProfileScreen(
    onBack: () -> Unit,
    onAddGame: () -> Unit,
    viewModel: PlayMatchViewModel = hiltViewModel(),
) {
    PlayMatchSystemBars()
    val state by viewModel.state.collectAsState()
    val profile = state.profile
    var gamerName by rememberSaveable { mutableStateOf("") }
    var gamerAvatarUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var gamerAvatarMediaType by rememberSaveable { mutableStateOf<String?>(null) }
    var bio by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("") }
    var languages by rememberSaveable { mutableStateOf("es") }
    var timezone by rememberSaveable { mutableStateOf(java.util.TimeZone.getDefault().id) }
    var playStyle by rememberSaveable { mutableStateOf("casual") }
    var voice by rememberSaveable { mutableStateOf(false) }
    var discoverable by rememberSaveable { mutableStateOf(false) }
    var adult by rememberSaveable { mutableStateOf(false) }
    var gameVisibility by rememberSaveable { mutableStateOf("connections") }
    var availabilityVisibility by rememberSaveable { mutableStateOf("connections") }
    var rankTolerance by rememberSaveable { mutableFloatStateOf(2f) }
    var voiceRequired by rememberSaveable { mutableStateOf(false) }
    var discoveryLevel by rememberSaveable { mutableFloatStateOf(50f) }
    var showAvailabilityEditor by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val gamerAvatarPicker = conectate.com.presentation.components.rememberInAppMediaPicker() { uri ->
        uri?.let { viewModel.uploadGamerAvatar(it) { uploaded, mediaType ->
            gamerAvatarUrl = uploaded
            gamerAvatarMediaType = mediaType
        } }
    }
    val gamerPhotoPicker = conectate.com.presentation.components.rememberInAppMediaPicker() { uri ->
        uri?.let(viewModel::uploadGamerPhoto)
    }
    LaunchedEffect(Unit) { viewModel.loadProfile() }
    LaunchedEffect(profile?.version, state.preferences?.version) {
        profile?.let {
            gamerName = it.displayName
            gamerAvatarUrl = it.avatarUrl
            gamerAvatarMediaType = it.avatarMediaType
            bio = it.gamerBio; region = it.regionCode; languages = it.languages.joinToString(", ").ifBlank { "es" }
            timezone = it.timezone; playStyle = it.playStyle; voice = it.voicePreference; discoverable = it.discoverable
            adult = it.adultConfirmed == true; gameVisibility = it.gameIdVisibility ?: "connections"
            availabilityVisibility = it.availabilityVisibility ?: "connections"
            rankTolerance = state.preferences?.rankTolerance?.toFloat() ?: 2f
            voiceRequired = state.preferences?.voiceRequired ?: false
            discoveryLevel = state.preferences?.discoveryLevel?.toFloat() ?: it.discoveryLevel.toFloat()
        }
    }
    PlayMatchNoticeHost(state, viewModel::clearNotice)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayMatchColors.Surface)
            .statusBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(PlayMatchDimensions.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(PlayMatchDimensions.SectionGap),
    ) {
        if (state.isLoading) { item { CenteredProgress("Cargando perfil…") }; return@LazyColumn }
        if (!state.capabilities.enabled || profile == null) {
            item { StatusCard(Icons.Default.Lock, "Perfil gamer no habilitado", "Esta cuenta todavía no tiene acceso a la prueba cerrada de Juegos.", "Volver", onBack) }
            return@LazyColumn
        }
        item { PlayMatchTopBar("Mi perfil gamer", onBack) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    CircularProfileImage(openMomentOnClick = false, ownerId = conectate.com.presentation.components.LocalProfileIdentityAccount.current, imageUrl = gamerAvatarUrl, mediaType = gamerAvatarMediaType, size = 80.dp, showBorder = true, borderColor = PlayMatchColors.Primary, borderWidth = 2.dp)
                    if (editing) {
                        IconButton(
                            onClick = { gamerAvatarPicker.launch(arrayOf("image/*", "video/mp4")) },
                            enabled = !state.isWorking,
                            modifier = Modifier.size(34.dp).background(PlayMatchColors.Accent, RoundedCornerShape(50)),
                        ) {
                            Icon(Icons.Default.PhotoCamera, "Cambiar foto del perfil gamer", tint = PlayMatchColors.Background, modifier = Modifier.size(19.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(gamerName.ifBlank { profile.displayName }, color = PlayMatchColors.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(profile.gamerBio.ifBlank { "Añade una bio gamer para presentarte." }, color = PlayMatchColors.SecondaryText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                IconButton(
                    onClick = { editing = !editing },
                    modifier = Modifier.background(PlayMatchColors.Card, RoundedCornerShape(50)),
                ) { Icon(if (editing) Icons.Default.Close else Icons.Default.Edit, if (editing) "Cerrar edición" else "Editar perfil gamer", tint = PlayMatchColors.Accent) }
            }
        }
        if (editing) {
        item { OutlinedTextField(gamerName, { gamerName = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Nombre gamer") }, singleLine = true, shape = RoundedCornerShape(10.dp), colors = playMatchTextFieldColors()) }
        item { OutlinedTextField(bio, { bio = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Biografía gamer") }, minLines = 2, shape = RoundedCornerShape(10.dp), colors = playMatchTextFieldColors()) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(region, { region = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Región amplia") }, supportingText = { Text("Ej.: LATAM. Nunca ubicación exacta.") }, shape = RoundedCornerShape(10.dp), colors = playMatchTextFieldColors())
                OutlinedTextField(languages, { languages = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Idiomas") }, supportingText = { Text("Separados por coma") }, shape = RoundedCornerShape(10.dp), colors = playMatchTextFieldColors())
            }
        }
        item { OutlinedTextField(timezone, { timezone = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Zona horaria") }, shape = RoundedCornerShape(10.dp), colors = playMatchTextFieldColors()) }
        item { ChoiceRow("Estilo", listOf("casual", "competitive", "mixed"), playStyle) { playStyle = it } }
        item { SettingSwitch("Prefiero chat de voz", voice) { voice = it } }
        item { SettingSwitch("Mostrarme en Juegos", discoverable, "El servidor aplica bloqueos, seguridad y disponibilidad.") { discoverable = it } }
        if (state.capabilities.adultConfirmationRequired) item { SettingSwitch("Confirmo que soy mayor de 18 años", adult, "PlayMatch usa un control conservador para esta prueba.") { adult = it } }
        item { ChoiceRow("Visibilidad del ID de juego", listOf("private", "connections", "public"), gameVisibility) { gameVisibility = it } }
        item { ChoiceRow("Visibilidad de horarios", listOf("private", "connections", "public"), availabilityVisibility) { availabilityVisibility = it } }
        item { SectionTitle("Preferencias de matchmaking") }
        item {
            Column {
                Text("Tolerancia de rango: ${rankTolerance.toInt()}", fontWeight = FontWeight.Medium)
                Slider(value = rankTolerance, onValueChange = { rankTolerance = it }, valueRange = 0f..5f, steps = 4)
            }
        }
        item { SettingSwitch("Solo jugadores con voz", voiceRequired) { voiceRequired = it } }
        item {
            Column {
                Text("Nivel de exploración: ${discoveryLevel.toInt()}%", fontWeight = FontWeight.Medium)
                Slider(value = discoveryLevel, onValueChange = { discoveryLevel = it }, valueRange = 0f..100f, steps = 9)
            }
        }
        item {
            Button(
                onClick = {
                    viewModel.saveProfile(UpdatePlayMatchProfileRequest(
                        expectedVersion = profile.version,
                        gamerDisplayName = gamerName.trim(), gamerAvatarUrl = gamerAvatarUrl,
                        gamerAvatarMediaType = gamerAvatarMediaType,
                        gamerBio = bio.trim(), regionCode = region.trim(),
                        languages = languages.split(',').map(String::trim).filter(String::isNotBlank),
                        playStyle = playStyle, voicePreference = voice, discoveryLevel = discoveryLevel.toInt(),
                        discoverable = discoverable, adultConfirmed = adult,
                        gameIdVisibility = gameVisibility, photosVisibility = profile.photosVisibility ?: "connections",
                        availabilityVisibility = availabilityVisibility, photos = profile.photos, timezone = timezone.trim(),
                    ))
                },
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Accent, contentColor = PlayMatchColors.Background),
            ) { Text("Guardar perfil") }
        }
        item {
            OutlinedButton(
                onClick = {
                    viewModel.savePreferences(SavePlayMatchPreferencesRequest(
                        expectedVersion = state.preferences?.version ?: 0,
                        gameIds = profile.games.map { it.gameId }.distinct(),
                        platforms = profile.games.map { it.platform }.distinct(),
                        rankTolerance = rankTolerance.toInt(),
                        roles = profile.games.map { it.role }.filter(String::isNotBlank).distinct(),
                        styles = listOf(playStyle),
                        languages = languages.split(',').map(String::trim).filter(String::isNotBlank),
                        regions = listOf(region.trim()).filter(String::isNotBlank),
                        voiceRequired = voiceRequired,
                        discoveryLevel = discoveryLevel.toInt(),
                    ))
                },
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                border = BorderStroke(1.dp, PlayMatchColors.Primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PlayMatchColors.White),
            ) { Text("Guardar preferencias de búsqueda") }
        }
        }
        item { SectionTitle("Mis juegos") }
        if (profile.games.isEmpty()) item { EmptyInline("Agrega tu primer juego para activar el matchmaking.") }
        items(profile.games, key = { it.id }) { game -> UserGameCard(game, onDelete = { viewModel.deleteGame(game.id) }) }
        item { AddGameDashedButton(onAddGame) }
        item { SectionTitle("Mis fotos") }
        item {
            ProfilePhotoRail(
                photos = profile.photos,
                enabled = state.capabilities.photosEnabled && !state.isWorking,
                onAdd = { gamerPhotoPicker.launch("image/*") },
                onRemove = viewModel::removeGamerPhoto,
            )
        }
        if (!state.capabilities.photosEnabled) {
            item { Text("Las capturas gamer todavía no están habilitadas para esta cuenta.", color = PlayMatchColors.SecondaryText, fontSize = 12.sp) }
        }
        item { SectionTitle("Disponibilidad", action = "Agregar horario", onAction = { showAvailabilityEditor = true }) }
        if (profile.availability.isEmpty()) item { EmptyInline("Añade horarios para mejorar las coincidencias.") }
        items(profile.availability, key = { it.id ?: "${it.dayOfWeek}-${it.startMinute}-${it.endMinute}" }) { slot ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { AvailabilityRow(slot) }
                IconButton(onClick = { viewModel.saveAvailability(profile.availability.filterNot { it == slot }) },
                    enabled = !state.isWorking) {
                    Icon(Icons.Default.Delete, "Eliminar horario", tint = PlayMatchColors.SecondaryText)
                }
            }
        }
    }
    if (showAvailabilityEditor && profile != null) AvailabilityDialog(
        onDismiss = { showAvailabilityEditor = false },
        onSave = { slot -> viewModel.saveAvailability(profile.availability + slot); showAvailabilityEditor = false },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddGameScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PlayMatchViewModel = hiltViewModel(),
) {
    PlayMatchSystemBars()
    val state by viewModel.state.collectAsState()
    var platform by rememberSaveable { mutableStateOf("") }
    var rank by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("") }
    var gameUserId by rememberSaveable { mutableStateOf("") }
    var visibility by rememberSaveable { mutableStateOf("connections") }
    var primary by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadProfile(); viewModel.prepareCatalogSearch() }
    val selected = state.selectedCatalogGame
    val rankOptions = remember(selected, platform) { PlayMatchUiPolicy.rankOptions(selected, platform) }
    val roleOptions = remember(selected) { PlayMatchUiPolicy.roleOptions(selected) }
    PlayMatchNoticeHost(state, viewModel::clearNotice)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayMatchColors.Surface)
            .statusBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(PlayMatchDimensions.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(PlayMatchDimensions.SectionGap),
    ) {
        item { PlayMatchCloseTopBar("Agregar juego", onBack) }
        if (state.isLoading) { item { CenteredProgress("Cargando catálogo…") }; return@LazyColumn }
        if (!state.capabilities.enabled) {
            item { StatusCard(Icons.Default.Lock, "Catálogo no habilitado", "Juegos permanece cerrado para esta cuenta.", "Volver", onBack) }
            return@LazyColumn
        }
        item {
            OutlinedTextField(
                state.catalogQuery,
                viewModel::updateCatalogQuery,
                Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar en el catálogo de juegos") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (state.catalogQuery.isNotBlank()) IconButton(onClick = { viewModel.updateCatalogQuery("") }) {
                        Icon(Icons.Default.Close, "Limpiar búsqueda")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(PlayMatchDimensions.CompactRadius),
                colors = playMatchTextFieldColors(),
            )
        }
        if (state.catalog.isNotEmpty()) {
            item { SectionTitle("Populares") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.catalog.take(20), key = { "popular-add-${it.id}" }) { game ->
                        PopularGameCard(game, compact = true, selected = selected?.id == game.id) {
                            platform = ""; rank = ""; role = ""; level = ""; gameUserId = ""
                            viewModel.selectCatalogGame(game)
                        }
                    }
                }
            }
        }
        if (selected != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(PlayMatchDimensions.CardRadius),
                    color = PlayMatchColors.Card,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectedGameHeader(selected)
                        if (state.isCatalogSelectionLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PlayMatchColors.Accent)
                        PlayMatchTextInput(gameUserId, { gameUserId = it.take(80) }, selected.gameUserIdLabel)
                        ChoiceRow("Plataforma", selected.platforms, platform) { platform = it }
                        if (PlayMatchUiPolicy.showsOfficialRank(selected) && rankOptions.isNotEmpty()) DefinitionChoiceRow("Tu rango / nivel", rankOptions, rank) { rank = it }
                        if (PlayMatchUiPolicy.showsPlayMatchLevel(selected)) PlayMatchTextInput(level, { level = it.take(60) }, "Nivel / experiencia")
                        if (roleOptions.isNotEmpty()) DefinitionChoiceRow("Rol principal", roleOptions, role) { role = it }
                        ChoiceRow("Visibilidad del ID", listOf("private", "connections", "public"), visibility) { visibility = it }
                        SettingSwitch("Juego principal", primary) { primary = it }
                        Button(
                            onClick = { viewModel.saveGame(SavePlayMatchGameRequest(selected.id, gameUserId.trim(), platform, rank, level.trim(), role, visibility, primary), onSaved) },
                            enabled = platform.isNotBlank() && !state.isWorking && !state.isCatalogSelectionLoading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Accent, contentColor = PlayMatchColors.Background),
                            shape = RoundedCornerShape(12.dp),
                        ) { if (state.isWorking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Guardar", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        item { SectionTitle(if (state.catalogQuery.isBlank()) "Explorar catálogo completo" else "Resultados") }
        if (state.isCatalogLoading) item { CenteredProgress("Buscando juegos…") }
        if (state.catalogError != null && !state.isCatalogLoading) item {
            StatusCard(Icons.Default.CloudOff, "No se pudo cargar el catálogo", state.catalogError.orEmpty(), "Reintentar", viewModel::retryCatalogSearch)
        }
        if (!state.isCatalogLoading && state.catalogError == null && state.catalogResults.isEmpty()) {
            item { EmptyInline("No encontramos juegos con ese nombre. Prueba otra búsqueda.") }
        }
        items(state.catalogResults, key = { "catalog-${it.id}" }) { game ->
            CatalogSearchResultCard(
                game = game,
                selected = selected?.id == game.id,
                onClick = {
                    platform = ""; rank = ""; role = ""; level = ""; gameUserId = ""
                    viewModel.selectCatalogGame(game)
                },
            )
        }
        if (state.catalogNextCursor != null) item {
            OutlinedButton(
                onClick = viewModel::loadMoreCatalog,
                enabled = !state.isCatalogLoadingMore,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                border = BorderStroke(1.dp, PlayMatchColors.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PlayMatchColors.Accent),
            ) {
                if (state.isCatalogLoadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Cargar más juegos")
            }
        }
    }
}

@Composable
fun PlayerDetailScreen(
    playerId: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: PlayMatchViewModel = hiltViewModel(),
) {
    PlayMatchSystemBars()
    val state by viewModel.state.collectAsState()
    var reportDialog by remember { mutableStateOf(false) }
    var blockDialog by remember { mutableStateOf(false) }
    var selectedPhotoIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(playerId) { viewModel.loadPlayer(playerId) }
    val player = state.playerDetail
    PlayMatchNoticeHost(state, viewModel::clearNotice)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayMatchColors.Surface)
            .statusBarsPadding(),
        contentPadding = PaddingValues(PlayMatchDimensions.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(PlayMatchDimensions.SectionGap),
    ) {
        item {
            PlayerDetailTopBar(
                onBack = onBack,
                onReport = { reportDialog = true },
                onBlock = { blockDialog = true },
            )
        }
        if (state.isLoading || player == null) { item { CenteredProgress("Cargando jugador…") }; return@LazyColumn }
        item { PlayerIdentityHeader(player) }
        item { PlayerMetaChips(player) }
        if (player.gamerBio.isNotBlank()) item { Text(player.gamerBio, color = PlayMatchColors.SecondaryText, fontSize = 16.sp, lineHeight = 23.sp) }
        item { SectionTitle("Juegos en común") }
        if (player.games.isEmpty()) item { EmptyInline("Aún no hay juegos públicos en común.") }
        items(player.games, key = { it.id }) { game ->
            CommonGameCard(
                game = game,
                reason = player.compatibilityReasons.firstOrNull { it.label.contains(game.gameName, ignoreCase = true) },
            )
        }
        if (player.photos.isNotEmpty()) {
            item { SectionTitle("Fotos") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(player.photos.size) { index ->
                        val url = player.photos[index]
                        AsyncImage(
                            model = url,
                            contentDescription = "Abrir foto gamer ${index + 1}",
                            modifier = Modifier
                                .size(128.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedPhotoIndex = index },
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                }
            }
        }
        if (player.availability.isNotEmpty()) {
            item { SectionTitle("Horarios compatibles") }
            items(player.availability, key = { it.id ?: "${it.dayOfWeek}-${it.startMinute}" }) { AvailabilityRow(it) }
        }
        item { Button(onClick = { onMessage(player.userId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Primary, contentColor = PlayMatchColors.White), shape = RoundedCornerShape(12.dp)) { Icon(Icons.AutoMirrored.Filled.Message, null); Spacer(Modifier.width(8.dp)); Text("Enviar mensaje", fontWeight = FontWeight.Bold) } }
        if (!player.connected) item { OutlinedButton(onClick = { viewModel.connect(player.userId) }, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), border = BorderStroke(1.dp, PlayMatchColors.Primary), colors = ButtonDefaults.outlinedButtonColors(contentColor = PlayMatchColors.White), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Conectar") } }
    }
    if (reportDialog && player != null) ConfirmTextDialog("Reportar jugador", "El equipo de seguridad revisará el perfil y esta interacción.", "Reportar", { reportDialog = false }) {
        viewModel.reportPlayer(player.userId, player.displayName, "conducta_inapropiada"); reportDialog = false
    }
    if (blockDialog && player != null) ConfirmTextDialog("Bloquear jugador", "No volverán a verse ni podrán interactuar en Novvin.", "Bloquear", { blockDialog = false }) {
        viewModel.blockPlayer(player.userId, onBack); blockDialog = false
    }
    selectedPhotoIndex?.let { initialIndex ->
        val items = player?.photos.orEmpty().mapIndexed { index, url ->
            MediaItem(id = "playmatch-${player?.userId}-$index", url = url, type = "image")
        }
        if (items.isNotEmpty()) {
            FullScreenImageDialog(
                mediaItems = items,
                initialIndex = initialIndex,
                onDismiss = { selectedPhotoIndex = null },
            )
        }
    }
}

@Composable
fun MatchFoundScreen(
    sessionId: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: PlayMatchViewModel = hiltViewModel(),
) {
    PlayMatchSystemBars()
    val state by viewModel.state.collectAsState()
    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }
    val session = state.session
    val match = session?.match
    val player = match?.player
    PlayMatchNoticeHost(state, viewModel::clearNotice)
    val cancelAndBack = { viewModel.cancelSession(onBack) }
    BackHandler(enabled = session?.status == "SEARCHING") { cancelAndBack() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayMatchColors.Surface)
            .statusBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.isLoading || session == null) { item { CenteredProgress("Buscando coincidencia…") }; return@LazyColumn }
        if (session.status == "SEARCHING" || player == null) {
            item { PlayMatchTopBar("PlayMatch", cancelAndBack) }
            item { Icon(Icons.Default.SportsEsports, null, Modifier.size(76.dp), tint = GamingEnd) }
            item { Text("Buscando compañero compatible", color = PlayMatchColors.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = PlayMatchColors.Accent, trackColor = PlayMatchColors.Border) }
            item { Text("Puedes cancelar en cualquier momento. La sesión vence automáticamente.", color = PlayMatchColors.SecondaryText) }
            item { OutlinedButton(onClick = cancelAndBack, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), border = BorderStroke(1.dp, PlayMatchColors.Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = PlayMatchColors.White)) { Text("Cancelar búsqueda") } }
            return@LazyColumn
        }
        item { Spacer(Modifier.height(18.dp)) }
        item { Text("¡Match!", color = PlayMatchColors.White, fontSize = 46.sp, fontWeight = FontWeight.Black) }
        item { Text("${match.score}% compatible", color = GamingEnd, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                CircularProfileImage(ownerId = state.profile?.userId, imageUrl = state.profile?.avatarUrl, mediaType = state.profile?.avatarMediaType, size = 92.dp, showBorder = true, borderColor = GamingStart, borderWidth = 3.dp)
                Surface(color = PlayMatchColors.Card, shape = RoundedCornerShape(50)) { Icon(Icons.Default.FavoriteBorder, "Coincidencia", Modifier.padding(14.dp), tint = GamingEnd) }
                CircularProfileImage(ownerId = player.userId, imageUrl = player.avatarUrl, mediaType = player.avatarMediaType, size = 92.dp, showBorder = true, borderColor = GamingStart, borderWidth = 3.dp)
            }
        }
        item { Text("¡Tú y ${player.displayName} tienen mucho en común!", color = PlayMatchColors.SecondaryText, fontSize = 17.sp) }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PlayMatchColors.Card), shape = RoundedCornerShape(PlayMatchDimensions.CardRadius)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Lo que comparten:", color = PlayMatchColors.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    match.reasons.forEach { ReasonRow(it) }
                }
            }
        }
        if (session.status == "ACCEPTED" || match.status == "ACCEPTED") {
            item { Button(onClick = { onMessage(player.userId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Primary, contentColor = PlayMatchColors.White), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.ChatBubbleOutline, null); Spacer(Modifier.width(8.dp)); Text("Enviar mensaje", fontWeight = FontWeight.Bold) } }
        } else {
            item { Button(onClick = { viewModel.decideMatch("ACCEPT") }, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Primary, contentColor = PlayMatchColors.White), shape = RoundedCornerShape(12.dp)) { Text("Aceptar match", fontWeight = FontWeight.Bold) } }
        }
        item { OutlinedButton(onClick = { viewModel.decideMatch("SKIP") }, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), border = BorderStroke(1.dp, PlayMatchColors.Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = PlayMatchColors.SecondaryText), shape = RoundedCornerShape(12.dp)) { Text("Seguir buscando", fontWeight = FontWeight.Bold) } }
    }
}

@Composable private fun PlayMatchHeader(
    gamerAvatarUrl: String?,
    gamerAvatarMediaType: String?,
    onProfile: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0D0A1D)
        ) {
            Image(
                painter = painterResource(R.drawable.novvin_playmatch_gamer),
                contentDescription = "Novvin PlayMatch",
                modifier = Modifier.padding(3.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("PlayMatch", color = PlayMatchColors.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onProfile, modifier = Modifier.background(Color(0xFF17122F), RoundedCornerShape(50))) {
            if (gamerAvatarUrl.isNullOrBlank()) {
                Icon(Icons.Default.AccountCircle, "Abrir mi perfil gamer", tint = PlayMatchColors.White)
            } else {
                CircularProfileImage(
                    ownerId = conectate.com.presentation.components.LocalProfileIdentityAccount.current,
                    imageUrl = gamerAvatarUrl,
                    mediaType = gamerAvatarMediaType,
                    size = 38.dp,
                    showBorder = true,
                    borderColor = PlayMatchColors.Accent,
                    borderWidth = 1.dp,
                )
            }
        }
    }
}

@Composable private fun PlayMatchTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack, modifier = Modifier.background(PlayMatchColors.Card, RoundedCornerShape(50))) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = PlayMatchColors.White) }; Spacer(Modifier.width(12.dp)); Text(title, color = PlayMatchColors.White, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun PlayerDetailTopBar(onBack: () -> Unit, onReport: () -> Unit, onBlock: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart).background(PlayMatchColors.Card, RoundedCornerShape(50)),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = PlayMatchColors.White) }
        Text("Detalle del jugador", color = PlayMatchColors.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.align(Alignment.CenterEnd)) {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.background(PlayMatchColors.Card, RoundedCornerShape(50)),
            ) { Icon(Icons.Default.MoreHoriz, "Más acciones", tint = PlayMatchColors.White) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Reportar") },
                    leadingIcon = { Icon(Icons.Default.Flag, null) },
                    onClick = { expanded = false; onReport() },
                )
                DropdownMenuItem(
                    text = { Text("Bloquear", color = PlayMatchColors.Danger) },
                    leadingIcon = { Icon(Icons.Default.Block, null, tint = PlayMatchColors.Danger) },
                    onClick = { expanded = false; onBlock() },
                )
            }
        }
    }
}

@Composable private fun PlayMatchCloseTopBar(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = PlayMatchColors.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
        IconButton(onClick = onClose, modifier = Modifier.background(PlayMatchColors.Card, RoundedCornerShape(50))) { Icon(Icons.Default.Close, "Cerrar", tint = PlayMatchColors.White) }
    }
}

@Composable
private fun PlayMatchHomeHero(
    featured: PlayMatchGameCatalogItem?,
    searching: Boolean,
    onMatch: () -> Unit,
    onDiscover: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = PlayMatchColors.Surface) {
        Box(Modifier.fillMaxWidth()) {
            if (featured != null) {
                GameArtwork(featured, Modifier.matchParentSize(), useCover = true)
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(
                    Color(0xFF111326), Color(0xF0111326), Color(0x9E111326),
                ))))
            }
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.SportsEsports, contentDescription = null, tint = PlayMatchColors.Accent, modifier = Modifier.size(30.dp))
                Text("Encuentra tu equipo", color = PlayMatchColors.White,
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Elige un juego, busca por plataforma o deja que PlayMatch encuentre una coincidencia.",
                    color = PlayMatchColors.White, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onMatch, colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Accent, contentColor = PlayMatchColors.Background)) {
                        Text(if (searching) "Ver búsqueda activa" else "Buscar partida")
                    }
                    OutlinedButton(onClick = onDiscover) { Text("Ver jugadores", color = PlayMatchColors.White) }
                }
            }
        }
    }
}

@Composable
private fun GamesShortcut(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp), color = PlayMatchColors.Surface,
        border = BorderStroke(1.dp, PlayMatchColors.Border)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = PlayMatchColors.Accent, modifier = Modifier.size(20.dp))
            Text(label, color = PlayMatchColors.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = PlayMatchColors.SecondaryText, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun GamesSearchField(value: String, onChange: (String) -> Unit, label: String, onSearch: () -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Default.Close, "Limpiar búsqueda") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(PlayMatchDimensions.CardRadius), colors = playMatchTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GamesCatalogRow(
    game: PlayMatchGameCatalogItem,
    saved: Boolean,
    canSave: Boolean,
    onPlayers: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = PlayMatchColors.Surface, border = BorderStroke(1.dp, PlayMatchColors.Border)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GameArtwork(game, Modifier.size(width = 62.dp, height = 76.dp).clip(RoundedCornerShape(10.dp)), useCover = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(game.name, color = PlayMatchColors.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (game.platforms.isNotEmpty()) {
                    Text(game.platforms.joinToString(" · "), color = PlayMatchColors.SecondaryText, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onPlayers, contentPadding = PaddingValues(0.dp)) {
                    Text("Ver jugadores", color = PlayMatchColors.Accent)
                }
            }
            if (canSave) IconButton(onSave) {
                Icon(if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    if (saved) "Quitar ${game.name} de guardados" else "Guardar ${game.name}", tint = PlayMatchColors.Accent)
            }
        }
    }
}

@Composable
private fun GamesLibraryRow(name: String, detail: String, onOpen: (() -> Unit)?, onRemove: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(15.dp), color = PlayMatchColors.Surface) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val action = if (onOpen == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onOpen)
            Row(Modifier.weight(1f).then(action).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, color = PlayMatchColors.White, fontWeight = FontWeight.Bold)
                    if (detail.isNotBlank()) Text(detail, color = PlayMatchColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                }
                if (onOpen != null) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = PlayMatchColors.Accent)
            }
            if (onRemove != null) IconButton(onRemove) { Icon(Icons.Default.Bookmark, "Quitar $name de guardados", tint = PlayMatchColors.Accent) }
        }
    }
}

private fun readinessMessage(readiness: PlayMatchStart): String = when (readiness) {
    PlayMatchStart.LOADING -> "Estamos cargando tu perfil gamer."
    PlayMatchStart.NO_PROFILE -> "Abre tu perfil gamer para preparar la búsqueda."
    PlayMatchStart.CONFIRM_AGE -> "Confirma tu edad en el perfil gamer para buscar partida."
    PlayMatchStart.ENABLE_DISCOVERY -> "Activa la visibilidad de tu perfil gamer para aparecer en las coincidencias."
    PlayMatchStart.ADD_GAME -> "Añade un juego y su plataforma para buscar compañeros."
    PlayMatchStart.READY -> "Tu perfil está preparado para buscar partida."
}

@Composable
private fun GamesProfileOverview(profile: PlayMatchProfile?, readiness: PlayMatchStart, openProfile: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = PlayMatchColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Mi perfil gamer", "Abrir", openProfile)
            if (profile != null) Text(profile.displayName, color = PlayMatchColors.White, fontWeight = FontWeight.Bold)
            Text(readinessMessage(readiness), color = PlayMatchColors.SecondaryText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GamesMatchPanel(
    state: PlayMatchUiState,
    readiness: PlayMatchStart,
    onProfile: () -> Unit,
    onAddGame: () -> Unit,
    onResume: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val status = state.session?.status
    Surface(shape = RoundedCornerShape(20.dp), color = PlayMatchColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Buscar partida", color = PlayMatchColors.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(when (status) {
                "SEARCHING" -> "Estamos buscando a alguien con quien puedas jugar. Puedes cancelar la búsqueda en cualquier momento."
                "MATCH_FOUND" -> "Encontramos una coincidencia. Revisa el perfil antes de decidir."
                else -> readinessMessage(readiness)
            }, color = PlayMatchColors.SecondaryText)
            if (status == "SEARCHING") {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = PlayMatchColors.Accent)
                OutlinedButton(onCancel, enabled = !state.isWorking) { Text("Cancelar búsqueda", color = PlayMatchColors.White) }
            } else if (status == "MATCH_FOUND") {
                Button(onResume) { Text("Ver coincidencia") }
            } else if (readiness == PlayMatchStart.READY) {
                val game = (state.catalog + state.catalogResults).firstOrNull { it.id == state.gameFilter }?.name
                    ?: state.profile?.games?.firstOrNull { it.gameId == state.gameFilter }?.gameName
                val filters = listOfNotNull(game, state.platformFilter).joinToString(" · ")
                Text(if (filters.isEmpty()) "Se usarán tus juegos y preferencias de PlayMatch." else "Buscando con $filters.",
                    color = PlayMatchColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                Button(onStart, enabled = !state.isWorking, colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Accent, contentColor = PlayMatchColors.Background)) {
                    Text(if (state.isWorking) "Iniciando…" else "Encontrar compañero")
                }
            } else if (readiness != PlayMatchStart.LOADING) {
                val addGame = readiness == PlayMatchStart.ADD_GAME
                Button(if (addGame) onAddGame else onProfile) { Text(if (addGame) "Añadir un juego" else "Abrir perfil gamer") }
            }
        }
    }
}

@Composable private fun FilterRail(games: List<PlayMatchGameCatalogItem>, selectedGame: String?, selectedPlatform: String?, onGame: (String?) -> Unit, onPlatform: (String?) -> Unit) {
    val selectedGameLabel = games.firstOrNull { it.id == selectedGame }?.let { it.shortName.ifBlank { it.name } } ?: "Todos"
    val platforms = games.flatMap { it.platforms }.distinct()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactFilterMenu(
            prefix = "JUEGO",
            selected = selectedGameLabel,
            options = listOf(null to "Todos") + games.take(40).map { it.id to it.shortName.ifBlank { it.name } },
            onSelected = onGame,
            modifier = Modifier.weight(1.15f),
        )
        CompactFilterMenu(
            prefix = "PLATAFORMA",
            selected = selectedPlatform ?: "Todas",
            options = listOf(null to "Todas") + platforms.map { it to it },
            onSelected = onPlatform,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CompactFilterMenu(
    prefix: String,
    selected: String,
    options: List<Pair<String?, String>>,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { expanded = true },
            color = Color(0xFF17142F),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFF30275D)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$prefix: $selected",
                    modifier = Modifier.weight(1f),
                    color = PlayMatchColors.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.KeyboardArrowDown, null, tint = PlayMatchColors.SecondaryText, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.distinctBy { it.first }.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelected(value) },
                )
            }
        }
    }
}

@Composable private fun PopularGameCard(game: PlayMatchGameCatalogItem, compact: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    val width = if (compact) 82.dp else 118.dp
    val regularWidth = 104.dp
    val height = if (compact) 116.dp else 150.dp
    Card(Modifier.width(if (compact) width else regularWidth).height(height).clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = PlayMatchColors.Card), border = if (selected) BorderStroke(2.dp, PlayMatchColors.Accent) else null) {
        Box(Modifier.fillMaxSize()) {
            GameArtwork(game, Modifier.fillMaxSize(), useCover = true)
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                color = Color.Black.copy(alpha = .72f),
                shape = RoundedCornerShape(50),
            ) {
                Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Group, null, tint = PlayMatchColors.Accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${game.userCount}", color = PlayMatchColors.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .92f)))).padding(horizontal = 8.dp, vertical = 9.dp)) {
                Text(game.shortName.ifBlank { game.name }, color = PlayMatchColors.White, fontSize = if (compact) 12.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun PlayerRecommendationCard(player: PlayMatchProfile, onOpen: (String) -> Unit, action: String, enabled: Boolean, onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(player.userId) }, shape = RoundedCornerShape(PlayMatchDimensions.CardRadius), colors = CardDefaults.cardColors(containerColor = Color(0xFF17142F)), border = BorderStroke(1.dp, Color(0xFF30275D))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProfileImage(ownerId = player.userId, imageUrl = player.avatarUrl, mediaType = player.avatarMediaType, size = 56.dp, showBorder = true, borderColor = PlayMatchColors.Primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(player.displayName, color = PlayMatchColors.White, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text(player.games.take(2).joinToString(" / ") { it.gameName }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF22B8CF)); Text(listOf(player.games.firstOrNull()?.platform, player.playStyle).filterNotNull().filter(String::isNotBlank).joinToString(" · "), fontSize = 12.sp, color = PlayMatchColors.SecondaryText, maxLines = 1) }
            Button(onClick = onConnect, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6), contentColor = PlayMatchColors.White), contentPadding = PaddingValues(horizontal = 15.dp), shape = RoundedCornerShape(11.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(action, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable private fun ConnectionRequestCard(connection: PlayMatchConnection, onAccept: () -> Unit, onReject: () -> Unit, enabled: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PlayMatchDimensions.CardRadius),
        colors = CardDefaults.cardColors(containerColor = PlayMatchColors.Card),
        border = BorderStroke(1.dp, PlayMatchColors.Border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(connection.otherName, color = PlayMatchColors.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (connection.message.isNotBlank()) Text(connection.message, color = PlayMatchColors.SecondaryText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAccept, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Primary, contentColor = PlayMatchColors.White),
                ) { Text("Aceptar", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onReject, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, PlayMatchColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PlayMatchColors.SecondaryText),
                ) { Text("Rechazar") }
            }
        }
    }
}

@Composable private fun IncomingMatchCard(match: PlayMatchMatch, onAccept: () -> Unit, onSkip: () -> Unit, onOpen: () -> Unit, enabled: Boolean) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(PlayMatchDimensions.CardRadius),
        colors = CardDefaults.cardColors(containerColor = PlayMatchColors.Card),
        border = BorderStroke(1.dp, PlayMatchColors.Border),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProfileImage(ownerId = match.player?.userId, imageUrl = match.player?.avatarUrl, mediaType = match.player?.avatarMediaType, size = 48.dp, showBorder = true, borderColor = GamingEnd)
                Column(Modifier.weight(1f)) { Text(match.player?.displayName ?: "Jugador", color = PlayMatchColors.White, fontWeight = FontWeight.Bold); Text("${match.score}% compatible", color = GamingEnd) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAccept, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Primary, contentColor = PlayMatchColors.White),
                ) { Text("Aceptar", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onSkip, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, PlayMatchColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PlayMatchColors.SecondaryText),
                ) { Text("Omitir") }
            }
        }
    }
}

@Composable private fun ScoreBadge(score: Int) { Surface(color = GamingStart.copy(alpha = .18f), shape = RoundedCornerShape(50)) { Text("${score.coerceIn(0, 100)}%", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = GamingStart, fontWeight = FontWeight.Bold) } }

@Composable private fun MatchPercentBadge(score: Int) { Surface(color = PlayMatchColors.Primary, shape = RoundedCornerShape(50)) { Text("${score.coerceIn(0, 100)}% Match", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = PlayMatchColors.White, fontWeight = FontWeight.Bold) } }

@Composable private fun UserGameCard(game: PlayMatchUserGame, onDelete: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(PlayMatchDimensions.CardRadius), colors = CardDefaults.cardColors(containerColor = PlayMatchColors.Card)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { GameArtwork(game.imageUrl, "game-${game.gameId}", Modifier.size(48.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(game.gameName, color = PlayMatchColors.White, fontWeight = FontWeight.Bold, fontSize = 17.sp); if (game.gameUserIdVisible && game.gameUserId.isNotBlank()) Text("ID: ${game.gameUserId}", color = PlayMatchColors.SecondaryText, fontSize = 13.sp) else Text(game.platform, color = PlayMatchColors.SecondaryText, fontSize = 13.sp) }; if (game.rank.isNotBlank() || game.level.isNotBlank()) Surface(color = PlayMatchColors.Input, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, PlayMatchColors.Accent.copy(alpha = .65f))) { Text(game.rank.ifBlank { game.level }, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = PlayMatchColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis) }; onDelete?.let { IconButton(it) { Icon(Icons.Default.Delete, "Eliminar ${game.gameName}", tint = PlayMatchColors.SecondaryText) } } } }
}

@Composable
private fun CommonGameCard(game: PlayMatchUserGame, reason: PlayMatchCompatibilityReason?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PlayMatchColors.Card,
        shape = RoundedCornerShape(PlayMatchDimensions.CardRadius),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GameArtwork(game.imageUrl, "common-game-${game.gameId}", Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(game.gameName, color = PlayMatchColors.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    reason?.label?.substringAfter('·')?.trim().orEmpty().ifBlank {
                        listOf(game.rank, game.platform).filter(String::isNotBlank).joinToString(" · ")
                    },
                    color = PlayMatchColors.SecondaryText,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.CheckCircle, "Juego compatible", tint = PlayMatchColors.Accent)
        }
    }
}

@Composable
private fun SelectedGameHeader(game: PlayMatchGameCatalogItem) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GameArtwork(game, Modifier.size(40.dp), useCover = false)
        Column(Modifier.weight(1f)) {
            Text("Configurar ${game.name}", color = PlayMatchColors.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(if (game.supportLevel == "FULLY_SUPPORTED") "Configuración especializada" else "Catálogo general PlayMatch", color = PlayMatchColors.Accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CatalogSearchResultCard(game: PlayMatchGameCatalogItem, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = PlayMatchColors.Card),
        border = if (selected) BorderStroke(2.dp, PlayMatchColors.Accent) else BorderStroke(1.dp, PlayMatchColors.Border),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameArtwork(game, Modifier.size(width = 72.dp, height = 84.dp), useCover = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(game.name, color = PlayMatchColors.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(game.platforms.take(3).joinToString(" · "), color = PlayMatchColors.SecondaryText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (game.supportLevel == "FULLY_SUPPORTED") "Rangos/roles configurados" else "Matching general disponible",
                    color = GamingEnd,
                    fontSize = 11.sp,
                )
            }
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.ChevronRight, null, tint = if (selected) PlayMatchColors.Accent else PlayMatchColors.SecondaryText)
        }
    }
}

@Composable
private fun GameArtwork(game: PlayMatchGameCatalogItem, modifier: Modifier, useCover: Boolean) {
    val url = if (useCover) game.coverUrl ?: game.logoUrl ?: game.iconUrl ?: game.imageUrl else game.logoUrl ?: game.iconUrl ?: game.coverUrl ?: game.imageUrl
    val usePhotoCrop = useCover && PlayMatchUiPolicy.hasDistinctCover(game)
    GameArtwork(
        url = url,
        cacheKey = "playmatch-${game.id}-${if (useCover) "cover" else "icon"}",
        modifier = modifier,
        fallbackLabel = game.shortName.ifBlank { game.name },
        contentScale = if (usePhotoCrop) {
            androidx.compose.ui.layout.ContentScale.Crop
        } else {
            androidx.compose.ui.layout.ContentScale.Fit
        },
        artworkPadding = if (usePhotoCrop) 0.dp else 12.dp,
    )
}

@Composable
private fun GameArtwork(
    url: String?,
    cacheKey: String,
    modifier: Modifier,
    fallbackLabel: String = "Juego",
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Fit,
    artworkPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF3A4162),
                        PlayMatchColors.Card,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            MissingGameArtwork(fallbackLabel)
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .addHeader("User-Agent", "Novvin/${BuildConfig.VERSION_NAME} (Android; https://conectate.com)")
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .build(),
                contentDescription = null,
                modifier = Modifier.matchParentSize().padding(artworkPadding),
                contentScale = contentScale,
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = PlayMatchColors.Accent,
                            strokeWidth = 2.dp,
                        )
                    }
                },
                error = {
                    val throwable = painter.state.let { state ->
                        (state as? coil.compose.AsyncImagePainter.State.Error)?.result?.throwable
                    }
                    LaunchedEffect(cacheKey, throwable) {
                        val httpStatus = (throwable as? coil.network.HttpException)?.response?.code
                        Log.w(
                            "PlayMatchArtwork",
                            "No se pudo cargar el arte de ${gameArtworkLogId(cacheKey)}: ${throwable?.javaClass?.simpleName ?: "Unknown"}${httpStatus?.let { " HTTP_$it" }.orEmpty()}",
                        )
                    }
                    MissingGameArtwork(fallbackLabel)
                },
            )
        }
    }
}

@Composable
private fun MissingGameArtwork(gameName: String) {
    Column(
        modifier = Modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            Icons.Default.BrokenImage,
            contentDescription = "Arte oficial de $gameName no disponible",
            tint = PlayMatchColors.SecondaryText,
            modifier = Modifier.size(28.dp),
        )
        Text(
            "Arte no disponible",
            color = PlayMatchColors.SecondaryText,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun gameArtworkLogId(cacheKey: String): String =
    cacheKey.removePrefix("playmatch-").take(80).replace(Regex("[^a-zA-Z0-9._-]"), "_")

@Composable private fun AvailabilityRow(slot: PlayMatchAvailability) { Surface(shape = RoundedCornerShape(50), color = PlayMatchColors.Card) { Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CalendarMonth, null, tint = GamingEnd); Spacer(Modifier.width(10.dp)); Text("${dayLabel(slot.dayOfWeek)} · ${minuteLabel(slot.startMinute)}–${minuteLabel(slot.endMinute)}", Modifier.weight(1f), color = PlayMatchColors.White); Text(slot.timezone, fontSize = 11.sp, color = PlayMatchColors.SecondaryText) } } }

@Composable private fun ReasonRow(reason: PlayMatchCompatibilityReason) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.CheckCircle, null, tint = GamingEnd, modifier = Modifier.size(18.dp)); Text(reason.label, Modifier.weight(1f), color = PlayMatchColors.SecondaryText); if (reason.points > 0) Text("+${reason.points}", color = GamingEnd, fontWeight = FontWeight.Bold) } }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ChoiceRow(title: String, values: List<String>, selected: String, onSelected: (String) -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, color = PlayMatchColors.SecondaryText, fontWeight = FontWeight.SemiBold); if (values.isEmpty()) Text("Sin opciones para este juego", color = PlayMatchColors.SecondaryText) else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { value -> PlayMatchFilterChip(humanize(value), selected == value) { onSelected(value) } } } } }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun DefinitionChoiceRow(title: String, values: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, color = PlayMatchColors.SecondaryText, fontWeight = FontWeight.SemiBold); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { (key, label) -> PlayMatchFilterChip(label, selected == key) { onSelected(key) } } } } }

@Composable private fun SettingSwitch(title: String, checked: Boolean, description: String? = null, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = PlayMatchColors.White, fontWeight = FontWeight.Medium); description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PlayMatchColors.SecondaryText) } }; Switch(checked, onChecked, colors = SwitchDefaults.colors(checkedThumbColor = PlayMatchColors.Background, checkedTrackColor = PlayMatchColors.Accent)) } }

@Composable private fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f), color = PlayMatchColors.White, fontSize = 18.sp, fontWeight = FontWeight.Black); if (action != null && onAction != null) TextButton(onAction, modifier = Modifier.heightIn(min = 44.dp), colors = ButtonDefaults.textButtonColors(contentColor = PlayMatchColors.Accent)) { Text(action, fontSize = 12.sp) } } }

@Composable private fun EmptyInline(text: String) { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = PlayMatchColors.Card) { Text(text, Modifier.padding(16.dp), color = PlayMatchColors.SecondaryText) } }

@Composable private fun CenteredProgress(label: String) { Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = PlayMatchColors.Accent); Spacer(Modifier.height(12.dp)); Text(label, color = PlayMatchColors.SecondaryText) } }

@Composable private fun StatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, onAction: () -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PlayMatchColors.Card), shape = RoundedCornerShape(PlayMatchDimensions.CardRadius)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, Modifier.size(36.dp), tint = GamingEnd); Text(title, color = PlayMatchColors.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(body, color = PlayMatchColors.SecondaryText); Button(onAction, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = PlayMatchColors.Primary, contentColor = PlayMatchColors.White)) { Text(action, fontWeight = FontWeight.Bold) } } } }

@Composable private fun ClosedTesterAccessCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, onAction: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = PlayMatchColors.Card, shape = RoundedCornerShape(PlayMatchDimensions.CardRadius), border = BorderStroke(1.dp, PlayMatchColors.Border)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(32.dp), tint = PlayMatchColors.Accent)
            Column(Modifier.weight(1f)) { Text(title, color = PlayMatchColors.White, fontWeight = FontWeight.Bold); Text(body, color = PlayMatchColors.SecondaryText, fontSize = 13.sp) }
            TextButton(onAction, colors = ButtonDefaults.textButtonColors(contentColor = PlayMatchColors.Accent)) { Text(action) }
        }
    }
}

@Composable
private fun PlayMatchFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick),
        color = if (selected) Color(0xFF8B5CF6) else Color(0xFF17142F),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) PlayMatchColors.Accent else Color(0xFF30275D)),
    ) { Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, color = PlayMatchColors.White, fontSize = 13.sp, maxLines = 1) } }
}

@Composable
private fun PlayMatchTextInput(value: String, onValueChange: (String) -> Unit, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = PlayMatchColors.SecondaryText, fontSize = 13.sp)
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = playMatchTextFieldColors())
    }
}

@Composable
private fun playMatchTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PlayMatchColors.White,
    unfocusedTextColor = PlayMatchColors.White,
    focusedContainerColor = PlayMatchColors.Input,
    unfocusedContainerColor = PlayMatchColors.Input,
    focusedBorderColor = PlayMatchColors.Accent,
    unfocusedBorderColor = Color.Transparent,
    focusedLeadingIconColor = PlayMatchColors.SecondaryText,
    unfocusedLeadingIconColor = PlayMatchColors.SecondaryText,
    focusedTrailingIconColor = PlayMatchColors.SecondaryText,
    unfocusedTrailingIconColor = PlayMatchColors.SecondaryText,
    focusedLabelColor = PlayMatchColors.Accent,
    unfocusedLabelColor = PlayMatchColors.SecondaryText,
    cursorColor = PlayMatchColors.Accent,
)

@Composable
private fun AddGameDashedButton(onClick: () -> Unit) {
    val borderColor = PlayMatchColors.Border
    Box(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Add, null, tint = PlayMatchColors.Accent); Spacer(Modifier.width(8.dp)); Text("Agregar juego", color = PlayMatchColors.Accent, fontWeight = FontWeight.Bold, fontSize = 17.sp) } }
}

@Composable
private fun ProfilePhotoRail(
    photos: List<String>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val visiblePhotos = photos.take(4)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(visiblePhotos, key = { it }) { url ->
            Box(Modifier.size(108.dp).clip(RoundedCornerShape(14.dp))) {
                AsyncImage(url, "Captura gamer", Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                IconButton(
                    onClick = { onRemove(url) },
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(30.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(50)),
                ) { Icon(Icons.Default.Close, "Eliminar captura gamer", tint = Color.White, modifier = Modifier.size(17.dp)) }
            }
        }
        items((0 until (4 - visiblePhotos.size)).toList()) { index ->
            Box(
                Modifier.size(108.dp).clip(RoundedCornerShape(14.dp)).background(PlayMatchColors.Card)
                    .border(1.dp, PlayMatchColors.Border, RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, "Agregar captura gamer ${visiblePhotos.size + index + 1}", tint = if (enabled) PlayMatchColors.Accent else PlayMatchColors.SecondaryText, modifier = Modifier.size(34.dp)) }
        }
    }
}

@Composable
private fun PlayerIdentityHeader(player: PlayMatchProfile) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PlayMatchColors.Card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, PlayMatchColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProfileImage(ownerId = player.userId, 
                imageUrl = player.avatarUrl,
                mediaType = player.avatarMediaType,
                fallbackName = player.displayName,
                size = 76.dp,
                borderColor = PlayMatchColors.Accent,
                borderWidth = 2.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = player.displayName,
                    color = PlayMatchColors.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                player.regionCode.takeIf(String::isNotBlank)?.let { region ->
                    Text(region, color = PlayMatchColors.SecondaryText, fontSize = 14.sp)
                }
            }
            player.compatibilityScore?.let { MatchPercentBadge(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerMetaChips(player: PlayMatchProfile) {
    val labels = listOf(player.regionCode, humanize(player.playStyle), player.languages.firstOrNull().orEmpty()).filter(String::isNotBlank)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label -> Surface(color = PlayMatchColors.Card, shape = RoundedCornerShape(10.dp)) { Text(label, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = PlayMatchColors.SecondaryText) } }
    }
}

@Composable private fun PlayMatchNoticeHost(state: PlayMatchUiState, dismiss: () -> Unit) {
    val text = state.error ?: state.message
    if (text != null) AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(dismiss, colors = ButtonDefaults.textButtonColors(contentColor = PlayMatchColors.Accent)) { Text("Entendido") } },
        title = { Text(if (state.error != null) "No se pudo completar" else "Juegos", color = PlayMatchColors.White) },
        text = { Text(text, color = PlayMatchColors.SecondaryText) },
        containerColor = PlayMatchColors.Card,
        titleContentColor = PlayMatchColors.White,
        textContentColor = PlayMatchColors.SecondaryText,
    )
}

@Composable private fun ConfirmTextDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { Button(onConfirm) { Text(confirm) } }, dismissButton = { TextButton(onDismiss) { Text("Cancelar") } }) }

@Composable private fun AvailabilityDialog(onDismiss: () -> Unit, onSave: (PlayMatchAvailability) -> Unit) {
    var day by rememberSaveable { mutableIntStateOf(1) }; var start by rememberSaveable { mutableStateOf("19:00") }; var end by rememberSaveable { mutableStateOf("22:00") }
    val startMinute = parseMinute(start)
    val endMinute = parseMinute(end)
    val valid = startMinute != null && endMinute != null && endMinute > startMinute
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Agregar disponibilidad") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { ChoiceRow("Día", (0..6).map(::dayLabel), dayLabel(day)) { day = (0..6).first { index -> dayLabel(index) == it } }; OutlinedTextField(start, { start = it.take(5) }, label = { Text("Inicio (HH:mm)") }); OutlinedTextField(end, { end = it.take(5) }, label = { Text("Fin (HH:mm)") }); Text("Se guardará en ${java.util.TimeZone.getDefault().id}", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { Button(onClick = { onSave(PlayMatchAvailability(dayOfWeek = day, startMinute = parseMinute(start)!!, endMinute = parseMinute(end)!!, timezone = java.util.TimeZone.getDefault().id)) }, enabled = valid) { Text("Guardar") } }, dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

@Composable
private fun PlayMatchSystemBars() {
    val view = LocalView.current
    var context = view.context
    var activity: Activity? = context as? Activity
    while (activity == null && context is ContextWrapper) {
        context = context.baseContext
        activity = context as? Activity
    }
    DisposableEffect(view, activity) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val previousLightStatus = controller?.isAppearanceLightStatusBars
        val previousLightNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (previousLightStatus != null) controller?.isAppearanceLightStatusBars = previousLightStatus
            if (previousLightNavigation != null) controller?.isAppearanceLightNavigationBars = previousLightNavigation
        }
    }
}

private fun criteriaFrom(state: PlayMatchUiState) = PlayMatchCriteria(
    gameIds = listOfNotNull(state.gameFilter), platforms = listOfNotNull(state.platformFilter),
    styles = state.preferences?.styles.orEmpty(), languages = state.preferences?.languages.orEmpty(),
    regions = state.preferences?.regions.orEmpty(), roles = state.preferences?.roles.orEmpty(), voiceRequired = state.preferences?.voiceRequired == true,
)
private fun dayLabel(day: Int) = listOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb").getOrElse(day) { "Día" }
private fun minuteLabel(minute: Int) = "%02d:%02d".format((minute / 60).coerceIn(0, 23), (minute % 60).coerceIn(0, 59))
private fun parseMinute(value: String): Int? { val parts = value.split(':'); if (parts.size != 2) return null; val hour = parts[0].toIntOrNull() ?: return null; val minute = parts[1].toIntOrNull() ?: return null; if (hour !in 0..23 || minute !in 0..59) return null; return hour * 60 + minute }
private fun humanize(value: String): String = when (value.trim().lowercase()) {
    "private" -> "Solo yo"
    "connections" -> "Conexiones"
    "public" -> "Público"
    "casual" -> "Casual"
    "competitive" -> "Competitivo"
    "mixed" -> "Mixto"
    else -> value.replace('-', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
