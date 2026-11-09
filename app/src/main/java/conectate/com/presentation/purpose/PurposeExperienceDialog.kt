package conectate.com.presentation.purpose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PurposeSource(val kind: String, val id: String, val parentId: String? = null)
internal val PurposeIndigo = Color(0xFF535BC1)
internal val PurposeMint = Color(0xFFB7E5DF)
internal val PurposePeach = Color(0xFFF8D1BB)
internal val PurposeInk = Color(0xFF263149)
internal fun PurposeState.draftText(name: String, fallback: String = "") = draft?.get(name)?.jsonPrimitive?.contentOrNull ?: fallback
internal fun purposeDate(value: String) = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("Fecha no disponible")
internal fun purposeLimit(value: String, limit: Int): String = value.substring(0,value.offsetByCodePoints(0,minOf(limit,value.codePointCount(0,value.length))))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurposeExperienceDialog(target: PurposeTarget, onDismiss: () -> Unit, onSource: (PurposeSource) -> Unit, viewModel: PurposeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(target,viewModel) { viewModel.open(target) }
    DisposableEffect(viewModel) { onDispose { viewModel.hideFromComposition() } }
    val snackbar = remember { SnackbarHostState() }
    var confirmRecovery by remember { mutableStateOf(false) }
    var confirmUnsavedExit by remember { mutableStateOf(false) }
    LaunchedEffect(state.error,state.notice) { (state.error ?: state.notice)?.let { snackbar.showSnackbar(it) } }
    val current = state.target ?: target
    val back: () -> Unit = {
        if(!state.recoveryReady && state.draft != null) confirmUnsavedExit = true
        else if (target.kind == "gaming" && current.kind != "gaming" && !state.busy) viewModel.open(target)
        else viewModel.close(onDismiss)
    }
    val source: (PurposeSource) -> Unit = { destination -> viewModel.close { onDismiss(); onSource(destination) } }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false,decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val light = MaterialTheme.colorScheme.surface.luminance() > .5f
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window,view).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        } }
        BackHandler(onBack = back)
        Scaffold(Modifier.fillMaxSize().imePadding(), bottomBar = {
            // Reserve space so messages never cover the next action at large text sizes.
            SnackbarHost(snackbar, Modifier.navigationBarsPadding())
        }, topBar = {
            TopAppBar(title = { Text(when(current.kind) { "social" -> "Cómo terminó"; "community" -> "Tu primera contribución"; else -> "Partidas acordadas" },maxLines = 1,overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = back, enabled = !state.busy || state.pending) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Volver") } },
                actions = { IconButton(onClick = viewModel::refresh, enabled = !state.busy && !state.loading) { Icon(Icons.Default.Refresh,"Actualizar") } })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if(state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { message ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer,shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(16.dp)) {
                                Text(message,color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = { when { state.recoveryConflict -> confirmRecovery = true; state.pending && state.supported && state.recoveryReady -> viewModel.retry(); else -> viewModel.refresh() } },enabled = !state.busy) { Text(if(state.recoveryConflict) "Revisar borrador guardado" else if(state.pending && state.recoveryReady) "Comprobar envío" else "Reintentar") }
                            }
                        }
                    }
                    if(state.pending) {
                        Text("Hay una acción sin confirmar. Quedó guardada en este dispositivo; comprobarla no la duplica.",style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = viewModel::retry,enabled = state.supported && state.recoveryReady && !state.busy) { Text("Comprobar acción pendiente") }
                    }
                    if(!state.loading && !state.supported) Text("Estas experiencias todavía no están habilitadas. Puedes volver a comprobarlo sin perder tu borrador.")
                    if(state.draftStale) Surface(color = MaterialTheme.colorScheme.secondaryContainer,shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Hubo cambios mientras editabas",style = MaterialTheme.typography.titleMedium)
                            val latest = state.payload?.get(if(current.kind == "social") "outcome" else "plan") as? JsonObject
                            Text(latest?.get(if(current.kind == "social") "conclusion" else "objective")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "La versión actual no tiene una conclusión publicada.")
                            Text("Tu borrador sigue debajo. Guardarlo reemplazará esta versión.")
                            TextButton(enabled = state.canAct,onClick = viewModel::keepDraftAgainstCurrentRevision) { Text("Revisé los cambios. Conservar mi borrador") }
                        }
                    }
                    key(state.accountId,current) {
                        state.payload?.let { body -> when(current.kind) {
                            "social" -> PurposeSocialContent(purposeJson.decodeFromJsonElement(body),state,viewModel,source)
                            "community" -> PurposeCommunityContent(purposeJson.decodeFromJsonElement(body),state,viewModel,source)
                            "gaming" -> PurposeGamingCatalog(purposeJson.decodeFromJsonElement(body),state,viewModel)
                            "plan" -> PurposeGamingPlan(purposeJson.decodeFromJsonElement(body),state,viewModel,source)
                            "proposal" -> PurposeGamingEditor(purposeJson.decodeFromJsonElement<GamingProposalContext>(body).games,state,null,viewModel)
                        } }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        if(confirmRecovery) AlertDialog(onDismissRequest = { confirmRecovery = false },title = { Text("¿Recuperar el borrador guardado?") },text = { Text("Reemplazará el texto visible por el que se guardó desde la otra pantalla. Puedes conservar esta pantalla para copiar tu texto antes.") },
            confirmButton = { TextButton(onClick = { confirmRecovery = false; viewModel.recoverSavedDraft() }) { Text("Recuperar guardado") } },dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text("Conservar mi texto") } })
        if(confirmUnsavedExit) AlertDialog(onDismissRequest = { confirmUnsavedExit = false },title = { Text("El último cambio no se ha guardado") },text = { Text("Si sales ahora, conservarás únicamente el borrador guardado anteriormente.") },
            confirmButton = { TextButton(onClick = { confirmUnsavedExit = false; viewModel.leaveWithoutSaving(onDismiss) }) { Text("Salir sin este cambio") } },dismissButton = { TextButton(onClick = { confirmUnsavedExit = false }) { Text("Conservar mi texto") } })
    }
}
