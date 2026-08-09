@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package conectate.com.presentation.gaming

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import conectate.com.data.model.*
import conectate.com.presentation.components.CircularProfileImage

private val GamingStart = Color(0xFF7847FF)
private val GamingEnd = Color(0xFF22D3EE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesHomeScreen(
    onOpenProfile: () -> Unit,
    onOpenAddGame: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    onMatchFound: (String) -> Unit,
    viewModel: PlayMatchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.session?.status, state.session?.id) {
        if (state.session?.status == "MATCH_FOUND") state.session?.id?.let(onMatchFound)
    }
    PlayMatchNoticeHost(state, viewModel::clearNotice)
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PlayMatchHeader(onOpenProfile) }
        if (state.isLoading) {
            item { CenteredProgress("Cargando Juegos…") }
            return@LazyColumn
        }
        if (!state.capabilities.enabled) {
            item {
                StatusCard(
                    icon = Icons.Default.SportsEsports,
                    title = "Juegos está en prueba cerrada",
                    body = "El módulo está instalado y protegido. Se habilitará únicamente para cuentas autorizadas por el servidor.",
                    action = "Volver a comprobar",
                    onAction = viewModel::refreshHome,
                )
            }
            return@LazyColumn
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar jugadores o juegos") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = viewModel::search) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Buscar")
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            )
        }
        item {
            MatchmakingCallToAction(
                enabled = !state.isWorking,
                searching = state.session?.status == "SEARCHING",
                onFind = { viewModel.startMatchmaking(criteriaFrom(state)) },
                onCancel = viewModel::cancelSession,
            )
        }
        item { SectionTitle("Filtros de búsqueda") }
        item {
            FilterRail(
                games = state.catalog,
                selectedGame = state.gameFilter,
                selectedPlatform = state.platformFilter,
                onGame = { viewModel.search(gameId = it) },
                onPlatform = { viewModel.search(platform = it) },
            )
        }
        item { SectionTitle("Juegos populares", action = "Agregar", onAction = onOpenAddGame) }
        item {
            if (state.catalog.isEmpty()) EmptyInline("El catálogo no está disponible.")
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.catalog.take(8), key = { it.id }) { game -> PopularGameCard(game) { viewModel.search(gameId = game.id) } }
            }
        }
        if (state.connections.any { it.direction == "INCOMING" && it.status == "PENDING" }) {
            item { SectionTitle("Solicitudes recibidas") }
            items(state.connections.filter { it.direction == "INCOMING" && it.status == "PENDING" }, key = { it.id }) { connection ->
                ConnectionRequestCard(connection, onAccept = { viewModel.decideConnection(connection.id, "ACCEPT") }, onReject = { viewModel.decideConnection(connection.id, "REJECT") })
            }
        }
        if (state.matches.any { it.status == "PENDING" }) {
            item { SectionTitle("Matches que esperan tu decisión") }
            items(state.matches.filter { it.status == "PENDING" }, key = { it.id }) { match ->
                IncomingMatchCard(
                    match = match,
                    onAccept = { viewModel.decideMatchById(match.id, "ACCEPT") },
                    onSkip = { viewModel.decideMatchById(match.id, "SKIP") },
                    onOpen = { match.player?.userId?.let(onOpenPlayer) },
                )
            }
        }
        item { SectionTitle("Jugadores recomendados") }
        if (state.players.isEmpty()) {
            item { EmptyInline("Aún no hay jugadores compatibles. Ajusta tus filtros o completa tu perfil gamer.") }
        } else {
            items(state.players, key = { it.userId }) { player -> PlayerRecommendationCard(player, onOpenPlayer) }
            if (state.nextCursor != null) item {
                OutlinedButton(onClick = viewModel::loadMore, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.isWorking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Ver más jugadores")
                }
            }
        }
    }
}

@Composable
fun GamingProfileScreen(
    onBack: () -> Unit,
    onAddGame: () -> Unit,
    viewModel: PlayMatchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val profile = state.profile
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
    LaunchedEffect(Unit) { viewModel.loadProfile() }
    LaunchedEffect(profile?.version, state.preferences?.version) {
        profile?.let {
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
        modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PlayMatchTopBar("Mi perfil gamer", onBack) }
        if (state.isLoading) { item { CenteredProgress("Cargando perfil…") }; return@LazyColumn }
        if (!state.capabilities.enabled || profile == null) {
            item { StatusCard(Icons.Default.Lock, "Perfil gamer no habilitado", "Esta cuenta todavía no tiene acceso a la prueba cerrada de Juegos.", "Volver", onBack) }
            return@LazyColumn
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProfileImage(imageUrl = profile.avatarUrl, size = 72.dp, showBorder = true)
                Column(Modifier.weight(1f)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (profile.discoverable) "Visible para matchmaking" else "Perfil oculto", color = if (profile.discoverable) GamingEnd else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { OutlinedTextField(bio, { bio = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Biografía gamer") }, minLines = 2) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(region, { region = it.take(40) }, Modifier.weight(1f), label = { Text("Región amplia") }, supportingText = { Text("Ej.: LATAM. Nunca ubicación exacta.") })
                OutlinedTextField(languages, { languages = it.take(80) }, Modifier.weight(1f), label = { Text("Idiomas") }, supportingText = { Text("Separados por coma") })
            }
        }
        item { OutlinedTextField(timezone, { timezone = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Zona horaria") }) }
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
                        gamerBio = bio.trim(), regionCode = region.trim(),
                        languages = languages.split(',').map(String::trim).filter(String::isNotBlank),
                        playStyle = playStyle, voicePreference = voice, discoveryLevel = profile.discoveryLevel,
                        discoverable = discoverable, adultConfirmed = adult,
                        gameIdVisibility = gameVisibility, photosVisibility = profile.photosVisibility ?: "connections",
                        availabilityVisibility = availabilityVisibility, photos = profile.photos, timezone = timezone.trim(),
                    ))
                },
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
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
            ) { Text("Guardar preferencias de búsqueda") }
        }
        item { SectionTitle("Mis juegos", action = "Agregar", onAction = onAddGame) }
        if (profile.games.isEmpty()) item { EmptyInline("Agrega tu primer juego para activar el matchmaking.") }
        items(profile.games, key = { it.id }) { game -> UserGameCard(game, onDelete = { viewModel.deleteGame(game.id) }) }
        item { SectionTitle("Disponibilidad", action = "Agregar horario", onAction = { showAvailabilityEditor = true }) }
        if (profile.availability.isEmpty()) item { EmptyInline("Añade horarios para mejorar las coincidencias.") }
        items(profile.availability, key = { it.id ?: "${it.dayOfWeek}-${it.startMinute}-${it.endMinute}" }) { slot -> AvailabilityRow(slot) }
        if (state.capabilities.photosEnabled) {
            item { SectionTitle("Fotos opcionales") }
            item { Text("Las fotos se moderan y respetan tu privacidad. La carga se habilitará desde el servicio seguro de medios.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    val state by viewModel.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedGameId by rememberSaveable { mutableStateOf<String?>(null) }
    var platform by rememberSaveable { mutableStateOf("") }
    var rank by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("") }
    var gameUserId by rememberSaveable { mutableStateOf("") }
    var visibility by rememberSaveable { mutableStateOf("connections") }
    var primary by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadProfile() }
    val selected = state.catalog.firstOrNull { it.id == selectedGameId }
    val filtered = state.catalog.filter { it.name.contains(query, true) }
    PlayMatchNoticeHost(state, viewModel::clearNotice)
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PlayMatchTopBar("Agregar juego", onBack) }
        if (state.isLoading) { item { CenteredProgress("Cargando catálogo…") }; return@LazyColumn }
        if (!state.capabilities.enabled) {
            item { StatusCard(Icons.Default.Lock, "Catálogo no habilitado", "Juegos permanece cerrado para esta cuenta.", "Volver", onBack) }
            return@LazyColumn
        }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar juego") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true) }
        item { SectionTitle("Catálogo") }
        if (filtered.isEmpty()) item { EmptyInline("No encontramos juegos con ese nombre.") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = PlayMatchUiPolicy.gameGridColumns(LocalConfiguration.current.screenWidthDp, LocalDensity.current.fontScale)) {
                filtered.forEach { game ->
                    FilterChip(
                        selected = selectedGameId == game.id,
                        onClick = { selectedGameId = game.id; platform = ""; rank = ""; role = "" },
                        label = { Text(game.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.SportsEsports, null, Modifier.size(18.dp)) },
                        modifier = Modifier.widthIn(min = 136.dp),
                    )
                }
            }
        }
        if (selected != null) {
            item { Text("Configurar ${selected.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { ChoiceRow("Plataforma", selected.platforms, platform) { platform = it } }
            if (selected.ranks.isNotEmpty()) item { ChoiceRow("Rango", selected.ranks, rank) { rank = it } }
            if (selected.roles.isNotEmpty()) item { ChoiceRow("Rol principal", selected.roles, role) { role = it } }
            item { OutlinedTextField(gameUserId, { gameUserId = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("ID dentro del juego (opcional)") }, singleLine = true) }
            item { OutlinedTextField(level, { level = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Nivel (opcional)") }, singleLine = true) }
            item { ChoiceRow("Quién puede ver tu ID", listOf("private", "connections", "public"), visibility) { visibility = it } }
            item { SettingSwitch("Juego principal", primary) { primary = it } }
            item {
                Button(
                    onClick = { viewModel.saveGame(SavePlayMatchGameRequest(selected.id, gameUserId.trim(), platform, rank, level.trim(), role, visibility, primary), onSaved) },
                    enabled = platform.isNotBlank() && !state.isWorking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                ) { if (state.isWorking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Guardar juego") }
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
    val state by viewModel.state.collectAsState()
    var reportDialog by remember { mutableStateOf(false) }
    var blockDialog by remember { mutableStateOf(false) }
    LaunchedEffect(playerId) { viewModel.loadPlayer(playerId) }
    val player = state.playerDetail
    PlayMatchNoticeHost(state, viewModel::clearNotice)
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PlayMatchTopBar("Detalle del jugador", onBack) }
        if (state.isLoading || player == null) { item { CenteredProgress("Cargando jugador…") }; return@LazyColumn }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CircularProfileImage(imageUrl = player.avatarUrl, size = 96.dp, showBorder = true, borderColor = GamingEnd, borderWidth = 2.dp)
                Spacer(Modifier.height(8.dp)); Text(player.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (player.regionCode.isNotBlank()) Text(player.regionCode, color = MaterialTheme.colorScheme.onSurfaceVariant)
                player.compatibilityScore?.let { ScoreBadge(it) }
            }
        }
        if (player.gamerBio.isNotBlank()) item { Text(player.gamerBio, style = MaterialTheme.typography.bodyLarge) }
        if (player.compatibilityReasons.isNotEmpty()) {
            item { SectionTitle("Por qué son compatibles") }
            items(player.compatibilityReasons, key = { it.code }) { reason -> ReasonRow(reason) }
        }
        item { SectionTitle("Juegos en común") }
        items(player.games, key = { it.id }) { game -> UserGameCard(game) }
        if (player.photos.isNotEmpty()) {
            item { SectionTitle("Fotos") }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(player.photos) { url -> AsyncImage(url, null, Modifier.size(128.dp).clip(RoundedCornerShape(14.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop) } } }
        }
        if (player.availability.isNotEmpty()) {
            item { SectionTitle("Horarios compatibles") }
            items(player.availability, key = { it.id ?: "${it.dayOfWeek}-${it.startMinute}" }) { AvailabilityRow(it) }
        }
        item {
            if (player.connected) {
                Button(onClick = { onMessage(player.userId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Icon(Icons.Default.Message, null); Spacer(Modifier.width(8.dp)); Text("Enviar mensaje") }
            } else {
                Button(onClick = { viewModel.connect(player.userId) }, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Solicitar conexión") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { reportDialog = true }, Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Default.Flag, null); Spacer(Modifier.width(6.dp)); Text("Reportar") }
                OutlinedButton(onClick = { blockDialog = true }, Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Block, null); Spacer(Modifier.width(6.dp)); Text("Bloquear") }
            }
        }
    }
    if (reportDialog && player != null) ConfirmTextDialog("Reportar jugador", "El equipo de seguridad revisará el perfil y esta interacción.", "Reportar", { reportDialog = false }) {
        viewModel.reportPlayer(player.userId, player.displayName, "conducta_inapropiada"); reportDialog = false
    }
    if (blockDialog && player != null) ConfirmTextDialog("Bloquear jugador", "No volverán a verse ni podrán interactuar en Novvin.", "Bloquear", { blockDialog = false }) {
        viewModel.blockPlayer(player.userId, onBack); blockDialog = false
    }
}

@Composable
fun MatchFoundScreen(
    sessionId: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: PlayMatchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }
    val session = state.session
    val match = session?.match
    val player = match?.player
    PlayMatchNoticeHost(state, viewModel::clearNotice)
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { PlayMatchTopBar("PlayMatch", onBack) }
        if (state.isLoading || session == null) { item { CenteredProgress("Buscando coincidencia…") }; return@LazyColumn }
        if (session.status == "SEARCHING" || player == null) {
            item { Icon(Icons.Default.SportsEsports, null, Modifier.size(76.dp), tint = GamingEnd) }
            item { Text("Buscando compañero compatible", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { Text("Puedes cancelar en cualquier momento. La sesión vence automáticamente.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { OutlinedButton(onClick = viewModel::cancelSession, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Cancelar búsqueda") } }
            return@LazyColumn
        }
        item { Text("¡Match!", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black) }
        item { Text("${match.score}% compatible", color = GamingEnd, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                CircularProfileImage(imageUrl = state.profile?.avatarUrl, size = 78.dp, showBorder = true, borderColor = GamingStart)
                Icon(Icons.Default.Favorite, "Coincidencia", tint = GamingEnd)
                CircularProfileImage(imageUrl = player.avatarUrl, size = 78.dp, showBorder = true, borderColor = GamingEnd)
            }
        }
        item { Text(player.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Lo que comparten", fontWeight = FontWeight.Bold)
                    match.reasons.forEach { ReasonRow(it) }
                }
            }
        }
        if (session.status == "ACCEPTED" || match.status == "ACCEPTED") {
            item { Button(onClick = { onMessage(player.userId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Icon(Icons.Default.Message, null); Spacer(Modifier.width(8.dp)); Text("Enviar mensaje") } }
        } else {
            item { Button(onClick = { viewModel.decideMatch("ACCEPT") }, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Aceptar match") } }
        }
        item { OutlinedButton(onClick = { viewModel.decideMatch("SKIP") }, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Seguir buscando") } }
    }
}

@Composable private fun PlayMatchHeader(onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(GamingStart, GamingEnd))), contentAlignment = Alignment.Center) { Icon(Icons.Default.SportsEsports, null, tint = Color.White) }
        Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("PlayMatch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black); Text("JUEGOS", fontSize = 10.sp, color = GamingEnd, letterSpacing = 1.sp) }
        IconButton(onClick = onProfile) { Icon(Icons.Default.AccountCircle, "Abrir mi perfil gamer") }
    }
}

@Composable private fun PlayMatchTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver") }; Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
}

@Composable private fun MatchmakingCallToAction(enabled: Boolean, searching: Boolean, onFind: () -> Unit, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(GamingStart, GamingEnd)))
            .clickable(enabled = enabled, role = Role.Button, onClick = if (searching) onCancel else onFind)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .semantics { contentDescription = if (searching) "Cancelar búsqueda de compañero" else "Encontrar compañero" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (searching) Icons.Default.Close else Icons.Default.Bolt, null, tint = Color.White, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(if (searching) "Buscando compañero…" else "Encontrar compañero", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text(if (searching) "Toca para cancelar" else "Match inteligente al instante", color = Color.White.copy(alpha = .85f), fontSize = 12.sp) }
            Icon(Icons.Default.ArrowForward, null, tint = Color.White)
        }
    }
}

@Composable private fun FilterRail(games: List<PlayMatchGameCatalogItem>, selectedGame: String?, selectedPlatform: String?, onGame: (String?) -> Unit, onPlatform: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selectedGame == null, { onGame(null) }, { Text("Todos") }) }
            items(games, key = { it.id }) { game -> FilterChip(selectedGame == game.id, { onGame(game.id) }, { Text(game.name) }) }
        }
        val platforms = games.flatMap { it.platforms }.distinct()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selectedPlatform == null, { onPlatform(null) }, { Text("Todas las plataformas") }) }
            items(platforms) { value -> FilterChip(selectedPlatform == value, { onPlatform(value) }, { Text(value) }) }
        }
    }
}

@Composable private fun PopularGameCard(game: PlayMatchGameCatalogItem, onClick: () -> Unit) {
    Card(Modifier.width(142.dp).heightIn(min = 126.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.SportsEsports, null, tint = GamingEnd, modifier = Modifier.size(34.dp)); Text(game.name, fontWeight = FontWeight.Bold, maxLines = 2); Text(game.platforms.take(2).joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun PlayerRecommendationCard(player: PlayMatchProfile, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(player.userId) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProfileImage(imageUrl = player.avatarUrl, size = 52.dp, showBorder = true)
            Column(Modifier.weight(1f)) { Text(player.displayName, fontWeight = FontWeight.Bold); Text(player.games.take(2).joinToString(" · ") { it.gameName }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant); player.compatibilityReasons.firstOrNull()?.let { Text(it.label, fontSize = 12.sp, color = GamingEnd, maxLines = 1) } }
            player.compatibilityScore?.let { ScoreBadge(it) }
        }
    }
}

@Composable private fun ConnectionRequestCard(connection: PlayMatchConnection, onAccept: () -> Unit, onReject: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(connection.otherName, fontWeight = FontWeight.Bold); if (connection.message.isNotBlank()) Text(connection.message); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onAccept) { Text("Aceptar") }; OutlinedButton(onReject) { Text("Rechazar") } } } }
}

@Composable private fun IncomingMatchCard(match: PlayMatchMatch, onAccept: () -> Unit, onSkip: () -> Unit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProfileImage(imageUrl = match.player?.avatarUrl, size = 48.dp, showBorder = true, borderColor = GamingEnd)
                Column(Modifier.weight(1f)) { Text(match.player?.displayName ?: "Jugador", fontWeight = FontWeight.Bold); Text("${match.score}% compatible", color = GamingEnd) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onAccept, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Aceptar") }; OutlinedButton(onSkip, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Omitir") } }
        }
    }
}

@Composable private fun ScoreBadge(score: Int) { Surface(color = GamingStart.copy(alpha = .18f), shape = RoundedCornerShape(50)) { Text("${score.coerceIn(0, 100)}%", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = GamingStart, fontWeight = FontWeight.Bold) } }

@Composable private fun UserGameCard(game: PlayMatchUserGame, onDelete: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.SportsEsports, null, tint = GamingEnd, modifier = Modifier.size(34.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(game.gameName, fontWeight = FontWeight.Bold); Text(listOf(game.platform, game.rank, game.role).filter(String::isNotBlank).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant); if (game.gameUserIdVisible && game.gameUserId.isNotBlank()) Text("ID: ${game.gameUserId}", fontSize = 12.sp) }; onDelete?.let { IconButton(it) { Icon(Icons.Default.Delete, "Eliminar ${game.gameName}") } } } }
}

@Composable private fun AvailabilityRow(slot: PlayMatchAvailability) { Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Schedule, null, tint = GamingEnd); Spacer(Modifier.width(10.dp)); Text("${dayLabel(slot.dayOfWeek)} · ${minuteLabel(slot.startMinute)}–${minuteLabel(slot.endMinute)}", Modifier.weight(1f)); Text(slot.timezone, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

@Composable private fun ReasonRow(reason: PlayMatchCompatibilityReason) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.CheckCircle, null, tint = GamingEnd, modifier = Modifier.size(18.dp)); Text(reason.label, Modifier.weight(1f)); if (reason.points > 0) Text("+${reason.points}", color = GamingEnd, fontWeight = FontWeight.Bold) } }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ChoiceRow(title: String, values: List<String>, selected: String, onSelected: (String) -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, fontWeight = FontWeight.SemiBold); if (values.isEmpty()) Text("Sin opciones para este juego", color = MaterialTheme.colorScheme.onSurfaceVariant) else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { value -> FilterChip(selected == value, { onSelected(value) }, { Text(humanize(value)) }) } } } }

@Composable private fun SettingSwitch(title: String, checked: Boolean, description: String? = null, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Switch(checked, onChecked) } }

@Composable private fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if (action != null && onAction != null) TextButton(onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) } } }

@Composable private fun EmptyInline(text: String) { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun CenteredProgress(label: String) { Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(label) } }

@Composable private fun StatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, onAction: () -> Unit) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, Modifier.size(48.dp), tint = GamingEnd); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant); Button(onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) } } } }

@Composable private fun PlayMatchNoticeHost(state: PlayMatchUiState, dismiss: () -> Unit) { val text = state.error ?: state.message; if (text != null) AlertDialog(onDismissRequest = dismiss, confirmButton = { TextButton(dismiss) { Text("Entendido") } }, title = { Text(if (state.error != null) "No se pudo completar" else "Juegos") }, text = { Text(text) }) }

@Composable private fun ConfirmTextDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { Button(onConfirm) { Text(confirm) } }, dismissButton = { TextButton(onDismiss) { Text("Cancelar") } }) }

@Composable private fun AvailabilityDialog(onDismiss: () -> Unit, onSave: (PlayMatchAvailability) -> Unit) {
    var day by rememberSaveable { mutableIntStateOf(1) }; var start by rememberSaveable { mutableStateOf("19:00") }; var end by rememberSaveable { mutableStateOf("22:00") }
    val valid = parseMinute(start) != null && parseMinute(end) != null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Agregar disponibilidad") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { ChoiceRow("Día", (0..6).map(::dayLabel), dayLabel(day)) { day = (0..6).first { index -> dayLabel(index) == it } }; OutlinedTextField(start, { start = it.take(5) }, label = { Text("Inicio (HH:mm)") }); OutlinedTextField(end, { end = it.take(5) }, label = { Text("Fin (HH:mm)") }); Text("Se guardará en ${java.util.TimeZone.getDefault().id}", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { Button(onClick = { onSave(PlayMatchAvailability(dayOfWeek = day, startMinute = parseMinute(start)!!, endMinute = parseMinute(end)!!, timezone = java.util.TimeZone.getDefault().id)) }, enabled = valid) { Text("Guardar") } }, dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
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
