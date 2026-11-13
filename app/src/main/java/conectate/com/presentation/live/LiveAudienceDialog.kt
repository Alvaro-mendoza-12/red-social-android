package conectate.com.presentation.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.LiveAudienceItem
import conectate.com.data.model.LiveAudienceState

@Composable
fun LiveAudienceDialog(
    liveId: String,
    currentUserId: String,
    onDismiss: () -> Unit,
    viewModel: LiveAudienceViewModel = hiltViewModel(key = "audience:$currentUserId:$liveId"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val created by viewModel.createdVersion.collectAsStateWithLifecycle()
    LifecycleResumeEffect(liveId, currentUserId) {
        viewModel.bind(liveId)
        onPauseOrDispose { viewModel.stop() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier
            .padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * .88f).dp)) {
            key(liveId, currentUserId) {
                LiveAudienceContent(state, error, busy, created, currentUserId, onDismiss,
                    viewModel::retry, viewModel::createPoll, viewModel::ask, viewModel::vote,
                    { item, status, answer, pin -> viewModel.update(item, status, answer, pin) })
            }
        }
    }
}

@Composable
internal fun liveAudienceTool(liveId: String, accountId: String, enabled: Boolean): (() -> Unit)? {
    var show by key(liveId, accountId) { rememberSaveable { mutableStateOf(false) } }
    if (show && enabled) LiveAudienceDialog(liveId, accountId, onDismiss = { show = false })
    return if (enabled) ({ show = true }) else null
}

@Composable
internal fun LiveAudienceContent(
    state: LiveAudienceState?,
    error: String?,
    busy: Boolean,
    createdVersion: Int,
    currentUserId: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCreatePoll: (String, List<String>, Int) -> Unit,
    onAsk: (String) -> Unit,
    onVote: (String, Int) -> Unit,
    onUpdate: (String, String?, String?, Boolean?) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf("poll") }
    var create by rememberSaveable { mutableStateOf(false) }
    var question by rememberSaveable { mutableStateOf("") }
    var submissionVersion by remember { mutableIntStateOf(createdVersion) }
    LaunchedEffect(createdVersion) {
        if (createdVersion != submissionVersion) {
            question = ""
            create = false
            submissionVersion = createdVersion
        }
    }
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Participa en el directo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cerrar participación") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == "poll", onClick = { tab = "poll" },
                label = { Text("Encuesta") }, leadingIcon = { Icon(Icons.Default.Poll, null) })
            FilterChip(selected = tab == "question", onClick = { tab = "question" },
                label = { Text("Preguntas") }, leadingIcon = { Icon(Icons.Default.QuestionAnswer, null) })
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, enabled = !busy) { Text("Actualizar") }
            }
            if (state == null) {
                if (error == null) CircularProgressIndicator(Modifier.size(24.dp))
            } else if (tab == "poll") {
                val polls = state.items.filter { it.kind == "poll" && it.status != "dismissed" }
                if (polls.isEmpty()) Text("Todavía no hay encuesta.")
                if (state.canModerate && polls.none { it.status == "open" }) {
                    if (create) PollComposer(busy, onCreatePoll)
                    else FilledTonalButton(onClick = { create = true }) { Text("Crear encuesta") }
                }
                polls.forEach { poll ->
                    PollCard(poll, state.canModerate, busy,
                        { index -> onVote(poll.id, index) },
                        { onUpdate(poll.id, "closed", null, null) })
                }
            } else {
                OutlinedTextField(value = question, onValueChange = { if (it.length <= 300) question = it },
                    label = { Text("Tu pregunta") }, minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Button(onClick = { onAsk(question) }, enabled = question.isNotBlank() && !busy) {
                    Text("Enviar pregunta")
                }
                val questions = state.items.filter { it.kind == "question" && it.status != "dismissed" }
                if (questions.isEmpty()) Text("Todavía no hay preguntas.")
                questions.forEach { item ->
                    key(item.id) { QuestionCard(item, state.canModerate, item.authorId == currentUserId, busy,
                        { onVote(item.id, 0) },
                        { status, answer, pin -> onUpdate(item.id, status, answer, pin) }) }
                }
            }
            if (state?.hasMore == true) Text("Se muestran las participaciones más recientes.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun PollComposer(busy: Boolean, onCreate: (String, List<String>, Int) -> Unit) {
    var content by rememberSaveable { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf(listOf("", "")) }
    var seconds by rememberSaveable { mutableIntStateOf(300) }
    OutlinedTextField(content, { if (it.length <= 300) content = it }, enabled = !busy,
        label = { Text("Pregunta de la encuesta") }, modifier = Modifier.fillMaxWidth())
    options.forEachIndexed { index, option ->
        OutlinedTextField(option, { value ->
            if (value.length <= 80) options = options.mapIndexed { i, old -> if (i == index) value else old }
        }, label = { Text("Respuesta ${index + 1}") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    }
    if (options.size < 6) TextButton(onClick = { options = options + "" }, enabled = !busy) { Text("Añadir respuesta") }
    Text("Duración", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(60, 300, 600).forEach { value ->
            FilterChip(selected = seconds == value, onClick = { seconds = value }, enabled = !busy,
                label = { Text("${value / 60} min") })
        }
    }
    val valid = content.isNotBlank() && options.all { it.isNotBlank() } &&
        options.map { it.trim().lowercase() }.distinct().size == options.size
    Button(onClick = { onCreate(content, options, seconds) }, enabled = valid && !busy) { Text("Publicar encuesta") }
}

@Composable
private fun PollCard(item: LiveAudienceItem, canModerate: Boolean, busy: Boolean, onVote: (Int) -> Unit, onClose: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.content, style = MaterialTheme.typography.titleMedium)
            item.options.forEachIndexed { index, option ->
                val count = item.counts.getOrElse(index) { 0 }
                val percent = if (item.totalVotes > 0) count.toFloat() / item.totalVotes else 0f
                OutlinedCard(onClick = { onVote(index) }, enabled = item.status == "open" && !busy,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = item.myChoice == index, onClick = null)
                            Text(option, Modifier.weight(1f))
                            Text("$count · ${(percent * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
                        }
                        LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Text("${item.totalVotes} ${if (item.totalVotes == 1) "voto" else "votos"} · ${if (item.status == "open") "Abierta" else "Finalizada"}",
                style = MaterialTheme.typography.bodySmall)
            if (canModerate && item.status == "open") TextButton(onClick = onClose, enabled = !busy) { Text("Cerrar encuesta") }
        }
    }
}

@Composable
private fun QuestionCard(item: LiveAudienceItem, canModerate: Boolean, own: Boolean, busy: Boolean,
    onVote: () -> Unit, onUpdate: (String?, String?, Boolean?) -> Unit) {
    var replying by rememberSaveable { mutableStateOf(false) }
    var answer by rememberSaveable { mutableStateOf("") }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.highlighted) Text("Pregunta destacada", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium)
            Text(item.authorName, fontWeight = FontWeight.SemiBold)
            Text(item.content)
            if (item.answer.isNotBlank()) {
                Text("Respuesta", style = MaterialTheme.typography.labelMedium)
                Text(item.answer)
            }
            TextButton(onClick = onVote, enabled = !busy && item.myChoice == null && item.status == "open") {
                Text("${if (item.myChoice != null) "Pregunta apoyada" else "Me interesa"} · ${item.totalVotes}")
            }
            if (canModerate) {
                Row {
                    TextButton(onClick = { replying = !replying }, enabled = !busy) { Text("Responder") }
                    TextButton(onClick = { onUpdate(null, null, !item.highlighted) }, enabled = !busy) {
                        Text(if (item.highlighted) "Quitar destacado" else "Destacar")
                    }
                }
                if (replying) {
                    OutlinedTextField(answer, { if (it.length <= 500) answer = it }, label = { Text("Respuesta del anfitrión") },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    Button(onClick = { onUpdate("answered", answer, null) }, enabled = answer.isNotBlank() && !busy) { Text("Publicar respuesta") }
                }
            }
            if (canModerate || own) TextButton(onClick = { onUpdate("dismissed", null, null) }, enabled = !busy) {
                Text(if (own) "Retirar pregunta" else "Ocultar pregunta")
            }
        }
    }
}
