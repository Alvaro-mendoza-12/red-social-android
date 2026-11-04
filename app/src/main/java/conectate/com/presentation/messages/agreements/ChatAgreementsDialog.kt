package conectate.com.presentation.messages.agreements

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

private val AgreementIndigo = Color(0xFF535BC1)
private val AgreementMint = Color(0xFFB7E5DF)
private val AgreementPeach = Color(0xFFF8D1BB)
private val AgreementInk = Color(0xFF263149)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAgreementsDialog(
    state: ChatAgreementsState,
    onClose: () -> Unit, onRefresh: () -> Unit, onDraft: (String) -> Unit,
    onSave: () -> Unit, onDismissEditor: () -> Unit, onRetry: () -> Unit,
    onKeepDraft: () -> Unit,
    onRespond: (ChatAgreement, String) -> Unit, onEdit: (ChatAgreement) -> Unit,
    onSource: (ChatAgreement) -> Unit, onDismissSource: () -> Unit,
    onRecoverSavedState: () -> Unit,
) {
    var confirmDiscard by remember(state.accountId, state.chatId) { mutableStateOf(false) }
    var cancel by remember(state.accountId, state.chatId) { mutableStateOf<ChatAgreement?>(null) }
    var compare by remember(state.accountId, state.chatId) { mutableStateOf(false) }
    var confirmRecovery by remember(state.accountId, state.chatId) { mutableStateOf(false) }
    val editing = state.sourceId != null
    val latest = state.agreements.find { it.id == state.editingId }
    val needsReview = latest != null && latest.version != state.editingVersion
    val requestRefresh = { if (state.recoveryConflict) confirmRecovery = true else onRefresh() }
    val requestBack = {
        if (editing && !state.busy && !state.retryPending && state.draft.isBlank()) onDismissEditor() else onClose()
    }
    Dialog(onDismissRequest = requestBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > .5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        BackHandler(onBack = requestBack)
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (editing) if (state.editingId == null) "Proponer acuerdo" else "Editar propuesta" else "Acuerdos") },
                    navigationIcon = { IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver al chat") } },
                    actions = { if (!editing) IconButton(onClick = requestRefresh, enabled = !state.busy && !state.loading) { Icon(Icons.Default.Refresh, "Actualizar acuerdos") } },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.error != null) item {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(state.error, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = if (state.retryPending && state.serverAvailable) onRetry else requestRefresh, enabled = !state.busy) {
                                    Text(when { state.recoveryConflict -> "Recuperar borrador guardado"; !state.serverAvailable -> "Volver a conectar"; state.retryPending -> "Reintentar envío"
                                        state.recoveryUnavailable -> "Reintentar recuperación"; else -> "Actualizar acuerdos" })
                                }
                            }
                        }
                    }
                    if (editing) item {
                        if (needsReview) {
                            Text("La propuesta tiene cambios posteriores. Compara los textos antes de guardar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { compare = true }) { Text("Comparar revisiones") }
                        }
                        AgreementEditor(state, onDraft, onSave, onDiscard = { confirmDiscard = true },
                            canSave = !needsReview && latest?.cancelled != true && !state.recoveryConflict && state.serverAvailable)
                    } else {
                        item {
                            Text("Lo que decidimos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Cada persona confirma por sí misma. Si cambia el texto, volvemos a confirmar.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        if (state.agreements.isEmpty() && !state.loading) item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Box(Modifier.size(64.dp).background(AgreementMint, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Handshake, null, Modifier.size(32.dp), tint = AgreementInk)
                                }
                                Text("Una propuesta con su contexto", style = MaterialTheme.typography.titleLarge)
                                Text("Mantén pulsado un mensaje del chat y elige Proponer acuerdo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(state.agreements, key = { it.id }) { agreement ->
                            AgreementCard(agreement, state.accountId.orEmpty(), enabled = !state.busy && !state.retryPending && !state.recoveryUnavailable && state.serverAvailable,
                                onRespond = { onRespond(agreement, it) }, onEdit = { onEdit(agreement) },
                                onCancel = { cancel = agreement }, onSource = { onSource(agreement) })
                        }
                    }
                }
            }
        }
        if (confirmDiscard) AlertDialog(
            onDismissRequest = { confirmDiscard = false }, title = { Text("¿Descartar este borrador?") },
            text = { Text("Se eliminará este borrador guardado en el dispositivo.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismissEditor() }) { Text("Descartar") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Seguir escribiendo") } },
        )
        if (confirmRecovery) AlertDialog(
            onDismissRequest = { confirmRecovery = false }, title = { Text("¿Volver al borrador guardado?") },
            text = { Text("Otra pantalla cambió la recuperación. El texto sin guardar de esta pantalla se sustituirá por el estado guardado en el dispositivo.") },
            confirmButton = { TextButton(onClick = { confirmRecovery = false; onRecoverSavedState() }) { Text("Recuperar estado guardado") } },
            dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text("Seguir revisando") } },
        )
        cancel?.let { agreement -> AlertDialog(
            onDismissRequest = { cancel = null }, title = { Text("¿Cancelar el acuerdo?") },
            text = { Text("Las dos personas verán que la propuesta se canceló.") },
            confirmButton = { TextButton(onClick = { cancel = null; onRespond(agreement, "cancel") }) { Text("Cancelar acuerdo") } },
            dismissButton = { TextButton(onClick = { cancel = null }) { Text("Conservar propuesta") } },
        ) }
        state.source?.let { source -> AlertDialog(
            onDismissRequest = onDismissSource, title = { Text("Mensaje de origen") },
            text = { Text(source.content, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = onDismissSource) { Text("Volver al acuerdo") } },
        ) }
        if (compare && latest != null) AlertDialog(
            onDismissRequest = { compare = false }, title = { Text("Revisar los cambios") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Texto actual", fontWeight = FontWeight.Bold)
                    Text(latest.text)
                    Text("Tu borrador", fontWeight = FontWeight.Bold)
                    Text(state.draft)
                }
            },
            confirmButton = { TextButton(onClick = { compare = false; onKeepDraft() }, enabled = !latest.cancelled) { Text("Conservar mi borrador") } },
            dismissButton = { TextButton(onClick = { compare = false }) { Text("Seguir revisando") } },
        )
    }
}

@Composable
private fun AgreementEditor(state: ChatAgreementsState, onDraft: (String) -> Unit, onSave: () -> Unit, onDiscard: () -> Unit, canSave: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (state.editingId == null) "¿Qué propones?" else "Una nueva revisión", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (state.editingId == null) "Escribe lo que quieres acordar. Ambos podrán confirmarlo o pedir cambios."
            else "Al guardar, las dos confirmaciones volverán a quedar pendientes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.draft, onValueChange = onDraft, enabled = !state.busy && !state.retryPending && !state.recoveryConflict,
            label = { Text("Propuesta") }, supportingText = { Text("${state.draft.length}/1000") }, minLines = 4, maxLines = 10,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        )
        if (state.savingDraft) Text("Guardando borrador…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (state.draftSaved) Text("Borrador guardado en este dispositivo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSave, enabled = canSave && state.draft.isNotBlank() && !state.busy && !state.retryPending,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AgreementIndigo, contentColor = Color.White)) {
            Text(if (state.editingId == null) "Enviar propuesta" else "Guardar nueva revisión")
        }
        TextButton(onClick = onDiscard, enabled = !state.busy && !state.retryPending && !state.recoveryConflict) { Text("Descartar borrador") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgreementCard(agreement: ChatAgreement, accountId: String, enabled: Boolean, onRespond: (String) -> Unit,
    onEdit: () -> Unit, onCancel: () -> Unit, onSource: () -> Unit) {
    Surface(shape = RoundedCornerShape(26.dp), tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(when (agreement.status) {
                "accepted" -> "Confirmado por ambos"
                "changes" -> "Hay cambios por conversar"
                "withdrawn" -> "Una confirmación se retiró"
                "cancelled" -> "Propuesta cancelada"
                else -> "Esperando confirmaciones"
            }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(agreement.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            agreement.participants.forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).background(if (member.response == "accepted") AgreementMint else AgreementPeach, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (member.response == "accepted") Icons.Default.Check else Icons.Default.PersonOutline, null, tint = AgreementInk, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (member.id == accountId) "Tú" else member.name, style = MaterialTheme.typography.titleSmall)
                        Text(when (member.response) { "accepted" -> "Confirmó esta revisión"; "changes" -> "Pidió cambios"; "withdrawn" -> "Retiró su confirmación"; else -> "Por confirmar" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Revisión ${agreement.proposalRevision}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (agreement.sourceAvailable) TextButton(onClick = onSource, enabled = enabled) { Text("Ver mensaje de origen") }
            else Text("Mensaje de origen no disponible", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!agreement.cancelled) {
                val own = agreement.participants.find { it.id == accountId }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (own?.response != "accepted") Button(onClick = { onRespond("accept") }, enabled = enabled && own != null,
                        colors = ButtonDefaults.buttonColors(containerColor = AgreementIndigo, contentColor = Color.White)) { Text("Confirmar") }
                    else OutlinedButton(onClick = { onRespond("withdraw") }, enabled = enabled) { Text("Retirar confirmación") }
                    if (own?.response != "changes") OutlinedButton(onClick = { onRespond("changes") }, enabled = enabled && own != null) { Text("Pedir cambios") }
                }
                if (agreement.authorId == accountId) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onEdit, enabled = enabled) { Text("Editar propuesta") }
                    TextButton(onClick = onCancel, enabled = enabled) { Text("Cancelar acuerdo") }
                }
            }
        }
    }
}
