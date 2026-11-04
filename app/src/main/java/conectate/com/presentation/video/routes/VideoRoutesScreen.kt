package conectate.com.presentation.video.routes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::changeQuery,
        onSizeChange = viewModel::changeSize,
        onPrepare = viewModel::prepare,
        onNewRoute = viewModel::showComposer,
        onShowRoute = viewModel::showRoute,
        onOpen = { viewModel.openVideo(it, onOpenVideo) },
        onUseful = viewModel::toggleUseful,
        onConclusionChange = viewModel::changeConclusion,
        onSave = { viewModel.saveConclusion() },
        onSaveAndBack = { viewModel.saveConclusion(onBack) },
        onCompleted = viewModel::setCompleted,
        onErase = viewModel::erase,
        onReload = viewModel::reload,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val route = state.route
    val enabled = state.accountId != null && !state.loading && !state.busy && !state.storageBlocked
    val unsavedConclusion = route != null && route.conclusion != state.conclusion
    val requestBack = {
        when {
            state.composerVisible && route != null && !state.busy -> onShowRoute()
            unsavedConclusion && !state.busy -> confirmLeave = true
            else -> onBack()
        }
    }
    BackHandler(onBack = requestBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Rutas de video") },
                navigationIcon = {
                    IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.loading) CircularProgressIndicator()
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (!state.busy) TextButton(onClick = onReload) { Text("Volver a cargar") }
                        }
                    }
                }
                if (!state.loading && state.composerVisible) {
                    Text("Elige qué te quieres llevar", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Explora un tema en una selección pequeña. Abre cada video y guarda lo que te sirva.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        label = { Text("¿Qué quieres explorar?") },
                        supportingText = { Text("Busca por palabras del catálogo, como guitarra o cocina.") },
                        singleLine = true,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VideoRoute.SIZES.forEach { count ->
                            FilterChip(
                                selected = count == state.requestedCount,
                                onClick = { onSizeChange(count) },
                                enabled = enabled,
                                label = { Text("$count videos") },
                            )
                        }
                    }
                    if (route != null) Text(
                        "Al preparar otra ruta se reemplazará la selección y su conclusión en este dispositivo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onPrepare, enabled = enabled && state.query.isNotBlank()) {
                        Text(if (route == null) "Preparar ruta" else "Preparar y reemplazar")
                    }
                    if (route != null) OutlinedButton(onClick = onShowRoute, enabled = !state.busy) { Text("Volver a mi ruta") }
                }
                if (!state.loading && !state.composerVisible && route != null) {
                    Text(
                        if (route.completed) "Ruta terminada" else route.topic,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (route.completed) Text(route.topic, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${route.items.size} videos de ${route.authorCount} " +
                            if (route.authorCount == 1) "creador" else "creadores",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (route.items.size < route.requestedCount) Text(
                        "Encontramos ${route.items.size} de los ${route.requestedCount} que pediste. Puedes recorrer esta selección o probar otro tema.",
                    )
                    Text("${route.openedCount} de ${route.items.size} abiertos. ${route.usefulCount} te sirvieron.")
                    route.items.forEachIndexed { index, item ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("${index + 1}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text(item.authorName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    OutlinedButton(onClick = { onOpen(item.videoId) }, enabled = enabled) {
                                        Icon(Icons.Default.PlayArrow, null)
                                        Text(if (item.opened) "Volver a abrir" else "Abrir video")
                                    }
                                    FilterChip(
                                        selected = item.useful,
                                        onClick = { onUseful(item.videoId) },
                                        enabled = enabled && item.opened,
                                        label = { Text("Me sirvió") },
                                        leadingIcon = if (item.useful) { { Icon(Icons.Default.Check, null) } } else null,
                                    )
                                }
                            }
                        }
                    }
                    Text("Lo que me llevo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = state.conclusion,
                        onValueChange = onConclusionChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        minLines = 3,
                        label = { Text("Mi conclusión") },
                        supportingText = { Text("${state.conclusion.length}/${VideoRoute.MAX_CONCLUSION}. Solo en este dispositivo y esta cuenta.") },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onSave, enabled = enabled && unsavedConclusion) { Text("Guardar conclusión") }
                        Button(onClick = { onCompleted(!route.completed) }, enabled = enabled) {
                            Text(if (route.completed) "Reabrir ruta" else "Terminar ruta")
                        }
                    }
                    Text(
                        "Puedes terminar cuando quieras y volver a los videos que elegiste. Tus marcas y tu conclusión son privadas.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onNewRoute, enabled = enabled) { Text("Crear otra ruta") }
                }
                if (!state.loading && (route != null || state.storageBlocked)) {
                    TextButton(onClick = { confirmErase = true }, enabled = !state.busy) { Text("Borrar ruta guardada") }
                }
            }
        }
    }
    if (confirmErase) AlertDialog(
        onDismissRequest = { confirmErase = false },
        title = { Text("¿Borrar esta ruta?") },
        text = { Text("Se borrarán la selección, las marcas y la conclusión de esta cuenta en este dispositivo.") },
        confirmButton = { TextButton(onClick = { confirmErase = false; onErase() }) { Text("Borrar ruta") } },
        dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Conservar") } },
    )
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("¿Guardar tu conclusión?") },
        text = { Text("Has escrito cambios que todavía no se han guardado.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onSaveAndBack() }) { Text("Guardar y salir") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Salir sin guardar") } },
    )
}
