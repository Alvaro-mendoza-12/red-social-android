package conectate.com.presentation.raffles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import conectate.com.data.raffles.*
import conectate.com.presentation.components.CircularProfileImage
import kotlinx.coroutines.delay

fun raffleStatusLabel(status: String) = when (status) {
    "draft" -> "Borrador"; "prepared" -> "Preparado"; "running" -> "En curso"
    "completed" -> "Completado"; "cancelled" -> "Cancelado"; else -> status
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RafflesScreen(onBack: () -> Unit, viewModel: RafflesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var creating by rememberSaveable { mutableStateOf(false) }
    var cancelDialog by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.resume()
            try { while (true) { delay(3000); viewModel.refresh() } } finally { viewModel.pause() }
        }
    }
    LaunchedEffect(state.detail?.raffle?.id) { if (state.detail != null) creating = false }
    val detail = state.detail
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (creating) "Crear sorteo" else detail?.raffle?.name ?: "Sorteos") },
            navigationIcon = { IconButton(onClick = {
                when { creating -> creating = false; detail != null -> viewModel.backToList(); else -> onBack() }
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            actions = { IconButton(onClick = { viewModel.refresh(); if (creating) viewModel.loadMembers() }) { Icon(Icons.Default.Refresh, "Actualizar desde el servidor") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            (state.error ?: if (state.retryOperation) "Hay una operación pendiente de confirmación. Reintenta para recuperar su resultado sin duplicarla." else null)?.let { error ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(onClick = { if (state.retryOperation) viewModel.retryPending() else { viewModel.refresh(); if (creating) viewModel.loadMembers() } }, enabled = !state.busy) {
                            Text(if (state.retryOperation) "Reintentar la misma operación" else "Actualizar y reintentar")
                        }
                    }
                }
            }
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when {
                creating -> key(state.accountId) { CreateRaffleForm(state, viewModel::create) }
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                detail == null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Sorteos de este ${if (viewModel.kind == "group") "grupo" else "espacio de comunidad"}", style = MaterialTheme.typography.titleMedium) }
                    if (state.canCreate) item { Button(onClick = { creating = true; viewModel.loadMembers() }, enabled = !state.busy && !state.retryOperation) { Text("+ Crear sorteo") } }
                    if (state.items.isEmpty() && state.error == null) item { Text(
                        if (viewModel.kind == "group") "Todavía no hay sorteos. Cualquier integrante del grupo puede crear uno."
                        else "Todavía no hay sorteos. Los administradores pueden crear uno con los miembros de esta comunidad."
                    ) }
                    items(state.items, key = { it.id }) { raffle ->
                        Card(Modifier.fillMaxWidth().clickable { viewModel.open(raffle.id) }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(raffle.name, style = MaterialTheme.typography.titleMedium)
                                Text("${raffleStatusLabel(raffle.status)} · ${raffle.participantCount} participantes · ${raffle.roundCount} rondas")
                                Text("Creado por ${raffle.creatorName} · ${raffle.createdAt.take(10)}", style = MaterialTheme.typography.bodySmall)
                                Text("Ver detalles", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (state.nextCursor != null) item { TextButton(onClick = viewModel::loadMore) { Text("Cargar más sorteos") } }
                }
                else -> key(state.accountId, detail.raffle.id) {
                    RaffleDetailContent(detail, state.busy || state.retryOperation, viewModel, onCancel = { cancelDialog = true })
                }
            }
        }
    }
    if (cancelDialog) AlertDialog(onDismissRequest = { cancelDialog = false }, title = { Text("¿Cancelar sorteo?") },
        text = { Text("Se detendrán los giros. Los resultados ya guardados se conservarán y no podrás iniciar más rondas en este sorteo.") },
        confirmButton = { TextButton(onClick = { cancelDialog = false; viewModel.command("cancel") }) { Text("Cancelar sorteo") } },
        dismissButton = { TextButton(onClick = { cancelDialog = false }) { Text("Continuar") } })
}

@Composable
internal fun CreateRaffleForm(state: RafflesUiState, onCreate: (String, String, List<String>, List<String>, Boolean) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var manualInput by rememberSaveable { mutableStateOf("") }
    var manuals by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.membersLoading, state.members) {
        if (state.membersComplete && !initialized) {
            selected = state.members.take(1000).map { it.id }; initialized = true
        } else if (state.membersComplete && initialized) {
            val eligible = state.members.mapTo(hashSetOf()) { it.id }
            selected = selected.filter { it in eligible }
        }
    }
    val preview = state.members.filter { it.id in selected } + manuals.mapIndexed { i, n -> RaffleParticipant("manual-$i", "MANUAL_PARTICIPANT", name = n) }
    val valid = state.canCreate && name.isNotBlank() && preview.size in 1..1000 && state.membersComplete && !state.busy && !state.retryOperation
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { OutlinedTextField(name, { name = it.take(120) }, label = { Text("Nombre del sorteo") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(description, { description = it.take(500) }, label = { Text("Descripción (opcional)") }, modifier = Modifier.fillMaxWidth()) }
        item { Text("${preview.size} seleccionados · ${state.totalMembers} miembros disponibles. Máximo 1.000 participantes.") }
        item { RaffleWheel(preview) }
        item { Text("La lista se congela al crear. Cambios posteriores en miembros o perfiles no alteran esta ronda.", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(search, { search = it }, label = { Text("Buscar participante") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = state.members.take((1000 - manuals.size).coerceAtLeast(0)).map { it.id } }, enabled = state.membersComplete) { Text("Seleccionar todos") }
                TextButton(onClick = { selected = emptyList() }, enabled = state.membersComplete) { Text("Deseleccionar todos") }
            }
        }
        if (state.membersLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()); Text("Importando miembros…") }
        if (state.membersComplete && state.members.isEmpty()) item { Text("No hay miembros elegibles. Puedes agregar participantes manuales para crear el sorteo.") }
        items(state.members.filter { it.name.contains(search, ignoreCase = true) }, key = { it.id }) { participant ->
            val checked = participant.id in selected
            Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = {
                if (checked) selected = selected - participant.id
                else if (preview.size < 1000) selected = selected + participant.id else formError = "Máximo 1.000 participantes."
            }, enabled = state.membersComplete).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(checked, onCheckedChange = null)
                CircularProfileImage(imageUrl = participant.avatarUrl, size = 36.dp)
                Column(Modifier.weight(1f)) { Text(participant.name); Text("Miembro · ${participant.userId?.takeLast(8)}", style = MaterialTheme.typography.labelSmall) }
            }
        }
        item {
            OutlinedTextField(manualInput, { manualInput = it.take(80) }, label = { Text("Participante manual") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = {
                val next = java.text.Normalizer.normalize(manualInput.trim(), java.text.Normalizer.Form.NFKC)
                when {
                    next.isBlank() -> formError = "Escribe un nombre."
                    manuals.any { it.equals(next, ignoreCase = true) } -> formError = "Ese nombre manual ya está en la lista."
                    preview.size >= 1000 -> formError = "Máximo 1.000 participantes."
                    else -> { manuals = manuals + next; manualInput = ""; formError = null; keyboard?.hide() }
                }
            }) { Text("+ Agregar participante") }
        }
        items(manuals, key = { "manual:$it" }) { manual ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$manual · Manual", Modifier.weight(1f))
                TextButton(onClick = { manuals = manuals - manual }) { Text("Quitar") }
            }
        }
        formError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        item { Button(onClick = { onCreate(name.trim(), description, selected, manuals, true) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Crear y comenzar automáticamente") } }
        item { OutlinedButton(onClick = { onCreate(name.trim(), description, selected, manuals, false) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Crear preparado para comenzar luego") } }
    }
}

@Composable
private fun RaffleDetailContent(detail: RaffleDetail, busy: Boolean, vm: RafflesViewModel, onCancel: () -> Unit) {
    val round = detail.currentRound
    // Keep the presentation queue above LazyColumn so scrolling the wheel out
    // of view cannot discard pending winners or replay an already revealed draw.
    val presentation = rememberRaffleDrawPresentationSession(round)
    val presenting = presentation.state.active != null || presentation.state.roundId != round?.id
    val results = round?.results.orEmpty()
    val remaining = round?.remainingParticipants() ?: detail.participants
    var expandedRound by rememberSaveable(detail.raffle.id) { mutableStateOf<String?>(null) }
    var showRemaining by rememberSaveable(detail.raffle.id) { mutableStateOf(false) }
    var showOriginal by rememberSaveable(detail.raffle.id) { mutableStateOf(false) }
    val visibleResults = if (presentation.state.roundId == round?.id)
        results.filter { it.position <= presentation.state.revealedPosition } else emptyList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("${raffleStatusLabel(detail.raffle.status)} · ${detail.raffle.participantCount} participantes · ${detail.raffle.roundCount} rondas") }
        item { Text("Creado por ${detail.raffle.creatorName} · ${detail.raffle.createdAt.take(10)}", style = MaterialTheme.typography.bodySmall) }
        if (detail.raffle.description.isNotBlank()) item { Text(detail.raffle.description) }
        item { Text("${if (detail.raffle.kind == "group") "Grupo" else "Comunidad"} · ${detail.raffle.roomId}", style = MaterialTheme.typography.bodySmall) }
        item(key = "draw-presentation") {
            RaffleDrawPresentation(round, detail.participants, presentation)
            if (round?.automatic == true && round.status == "running") Text("Modo automático activo en el servidor. Puedes salir y volver sin reiniciar la ronda.")
        }
        if (detail.canManage) item {
            when (detail.raffle.status) {
                "prepared" -> Button(onClick = { vm.command("start", true) }, enabled = !busy) { Text("Comenzar automáticamente") }
                "running" -> {
                    Button(onClick = { vm.command("automatic", round?.automatic != true) }, enabled = !busy) { Text(if (round?.automatic == true) "Pausar automático" else "Continuar automático") }
                    if (round?.automatic != true) OutlinedButton(onClick = { vm.command("spin") }, enabled = !busy && !presenting) { Text("Girar siguiente posición") }
                }
                "completed" -> Button(onClick = { vm.command("new-round", true) }, enabled = !busy && !presenting) { Text("Nueva ronda") }
            }
            if (detail.raffle.status in setOf("prepared", "running")) TextButton(onClick = onCancel, enabled = !busy) { Text("Cancelar sorteo") }
        }
        item { Text("Resultados de ronda ${round?.number ?: "—"}", style = MaterialTheme.typography.titleMedium) }
        items(visibleResults, key = { "result-${round?.id}-${it.position}" }) { result ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${result.position}.º")
                CircularProfileImage(result.participant.avatarUrl, size = 32.dp)
                Text(result.participant.name + if (result.participant.kind == "MANUAL_PARTICIPANT") " · Manual" else "")
            }
        }
        item { TextButton(onClick = { showRemaining = !showRemaining }) { Text("${if (showRemaining) "Ocultar" else "Ver"} participantes restantes (${remaining.size})") } }
        if (showRemaining) itemsIndexed(remaining, key = { _, p -> "remaining-${p.id}" }) { index, participant ->
            ParticipantRow(participant, index + 1)
        }
        item { TextButton(onClick = { showOriginal = !showOriginal }) { Text("${if (showOriginal) "Ocultar" else "Ver"} participantes originales (${detail.participants.size})") } }
        if (showOriginal) items(detail.participants, key = { "original-${it.id}" }) { participant -> ParticipantRow(participant) }
        item { Text("Historial de rondas", style = MaterialTheme.typography.titleMedium) }
        detail.rounds.forEach { history ->
          item(key = "history-${history.id}") {
            Card(Modifier.fillMaxWidth().clickable { expandedRound = if (expandedRound == history.id) null else history.id }) {
                Column(Modifier.padding(12.dp)) {
                    Text("Ronda ${history.number} · ${raffleStatusLabel(history.status)} · ${history.results.size} resultados")
                    Text("${history.startedAt.take(10)} · Ver detalles", style = MaterialTheme.typography.bodySmall)
                }
            }
          }
          if (expandedRound == history.id) {
            item(key = "history-info-${history.id}") { Text("Inicio: ${history.startedAt} · Fin: ${history.finishedAt ?: "en curso"} · Iniciado por: ${history.initiatedBy}", style = MaterialTheme.typography.bodySmall) }
            items(history.results, key = { "history-result-${history.id}-${it.position}" }) { Text("${it.position}.º ${it.participant.name}") }
          }
        }
        if (detail.nextRound != null) item { TextButton(onClick = vm::loadMore) { Text("Cargar rondas anteriores") } }
    }
}

@Composable
private fun ParticipantRow(participant: RaffleParticipant, segment: Int? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        segment?.let { Text("$it.") }
        CircularProfileImage(imageUrl = participant.avatarUrl, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(participant.name)
            Text(if (participant.kind == "MANUAL_PARTICIPANT") "Manual" else "Miembro · ${participant.userId?.takeLast(8)}", style = MaterialTheme.typography.labelSmall)
        }
    }
}
