package conectate.com.presentation.video.routes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun VideoRoutesScreen(
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit,
    viewModel: VideoRoutesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VideoRoutesContent(
        state = state, onBack = onBack, onQueryChange = viewModel::changeQuery,
        onSizeChange = viewModel::changeSize, onPrepare = viewModel::prepare,
        onNewRoute = viewModel::showComposer, onShowRoute = viewModel::showRoute,
        onOpen = { viewModel.openVideo(it, onOpenVideo) }, onUseful = viewModel::toggleUseful,
        onConclusionChange = viewModel::changeConclusion, onSave = { viewModel.saveConclusion() },
        onSaveAndBack = { viewModel.saveConclusion(onBack) }, onCompleted = viewModel::setCompleted,
        onErase = viewModel::erase, onReload = viewModel::reload,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoRoutesContent(
    state: VideoRoutesState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSizeChange: (Int) -> Unit,
    onPrepare: () -> Unit,
    onNewRoute: () -> Unit,
    onShowRoute: () -> Unit,
    onOpen: (String) -> Unit,
    onUseful: (String) -> Unit,
    onConclusionChange: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAndBack: () -> Unit,
    onCompleted: (Boolean) -> Unit,
    onErase: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmErase by remember(state.accountId) { mutableStateOf(false) }
    var confirmLeave by remember(state.accountId) { mutableStateOf(false) }
    var confirmReload by remember(state.accountId) { mutableStateOf(false) }
    var menuOpen by remember(state.accountId) { mutableStateOf(false) }
    var noteOpen by remember(state.accountId, state.route?.id) { mutableStateOf(false) }
    var noteSaveRequested by remember(state.accountId, state.route?.id) { mutableStateOf(false) }
    val route = state.route
    val enabled = state.accountId != null && !state.loading && !state.busy && !state.storageBlocked
    val unsavedConclusion = route != null && route.conclusion != state.conclusion
    LaunchedEffect(noteSaveRequested, state.busy, unsavedConclusion, state.error) {
        if (noteSaveRequested && !state.busy) {
            if (!unsavedConclusion && state.error == null) noteOpen = false
            noteSaveRequested = false
        }
    }
    val requestBack = {
        when {
            state.composerVisible && route != null && !state.busy -> onShowRoute()
            unsavedConclusion && !state.busy -> confirmLeave = true
            else -> onBack()
        }
    }
    BackHandler(onBack = requestBack)
    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Rutas", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    if (route != null || state.storageBlocked) Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !state.busy) {
                            Icon(Icons.Default.MoreHoriz, "Opciones de la ruta")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (route != null) DropdownMenuItem(
                                text = { Text("Crear otra ruta") }, leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { menuOpen = false; onNewRoute() }, enabled = enabled,
                            )
                            DropdownMenuItem(
                                text = { Text("Borrar ruta guardada") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                onClick = { menuOpen = false; confirmErase = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!state.loading && state.composerVisible) Surface(tonalElevation = 2.dp) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(20.dp, 12.dp)) {
                        Button(
                            onClick = onPrepare, enabled = enabled && state.query.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RouteIndigo, contentColor = Color.White),
                        ) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Route, null)
                            Spacer(Modifier.width(10.dp))
                            Text(if (route == null) "Preparar ruta" else "Preparar y reemplazar")
                        }
                    }
                }
            }
        },
    ) { padding ->
        key(state.accountId) {
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 880.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp, 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally()))
                    if (state.busy && !state.composerVisible) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { error ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                                Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                                if (!state.busy && (state.storageBlocked || !state.composerVisible)) TextButton(onClick = {
                                    if (unsavedConclusion) confirmReload = true else onReload()
                                }) { Text("Volver a cargar") }
                            }
                        }
                    }
                    if (!state.loading && state.composerVisible) VideoRouteDiscovery(
                        state = state, enabled = enabled, onQueryChange = onQueryChange, onSizeChange = onSizeChange,
                        onPrepare = onPrepare, onShowRoute = onShowRoute,
                    )
                    if (!state.loading && !state.composerVisible && route != null) key(route.id) {
                        VideoRouteCollection(
                            route = route, conclusion = state.conclusion, unsavedConclusion = unsavedConclusion,
                            enabled = enabled, onOpen = onOpen, onUseful = onUseful,
                            onEditConclusion = { noteOpen = true }, onCompleted = onCompleted,
                        )
                    }
                }
            }
        }
    }
    if (noteOpen && route != null) ModalBottomSheet(
        onDismissRequest = { noteOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Lo que me llevo", style = MaterialTheme.typography.headlineSmall)
            Text("Una idea para recordar cuando vuelvas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.conclusion, onValueChange = onConclusionChange, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                label = { Text("Mi conclusión") },
                supportingText = { Text("${state.conclusion.length}/${VideoRoute.MAX_CONCLUSION}. Solo para ti en este dispositivo.") },
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { noteSaveRequested = true; onSave() }, enabled = enabled && unsavedConclusion,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RouteIndigo, contentColor = Color.White),
            ) { Text("Guardar conclusión") }
        }
    }
    if (confirmErase) AlertDialog(
        onDismissRequest = { confirmErase = false }, title = { Text("¿Borrar esta ruta?") },
        text = { Text("Se borrarán la selección, las marcas y la conclusión de esta cuenta en este dispositivo.") },
        confirmButton = { TextButton(onClick = { confirmErase = false; onErase() }) { Text("Borrar ruta") } },
        dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Conservar") } },
    )
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false }, title = { Text("¿Guardar tu conclusión?") },
        text = { Text("Has escrito cambios que todavía no se han guardado.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onSaveAndBack() }) { Text("Guardar y salir") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Salir sin guardar") } },
    )
    if (confirmReload) AlertDialog(
        onDismissRequest = { confirmReload = false }, title = { Text("¿Cargar la ruta guardada?") },
        text = { Text("Se descartarán los cambios de esta conclusión que todavía no has guardado.") },
        confirmButton = { TextButton(onClick = { confirmReload = false; onReload() }) { Text("Cargar lo guardado") } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("Conservar cambios") } },
    )
}
