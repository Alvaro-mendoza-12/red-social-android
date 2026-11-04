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
internal val PurposeState.canAct get() = supported && recoveryReady && !loading && !busy && !pending
internal fun PurposeState.draftText(name: String, fallback: String = "") = draft?.get(name)?.jsonPrimitive?.contentOrNull ?: fallback
internal fun purposeDate(value: String) = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("Fecha no disponible")
internal fun purposeLimit(value: String, limit: Int): String = value.substring(0,value.offsetByCodePoints(0,minOf(limit,value.codePointCount(0,value.length))))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurposeExperienceDialog(target: PurposeTarget, onDismiss: () -> Unit, onSource: (PurposeSource) -> Unit, viewModel: PurposeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(target) { viewModel.open(target) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error,state.notice) { (state.error ?: state.notice)?.let { snackbar.showSnackbar(it) } }
    val current = state.target ?: target
    val back: () -> Unit = {
        if (target.kind == "gaming" && current.kind != "gaming" && !state.busy) viewModel.open(target)
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
        Scaffold(Modifier.fillMaxSize().imePadding(), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Text(when(current.kind) { "social" -> "Cómo terminó"; "community" -> "Tu primera contribución"; else -> "Partidas acordadas" }) },
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
                                TextButton(onClick = if(state.pending && state.supported && state.recoveryReady) viewModel::retry else viewModel::refresh,enabled = !state.busy) { Text(if(state.pending && state.recoveryReady) "Comprobar envío" else "Reintentar") }
                            }
                        }
                    }
                    if(state.pending) {
                        Text("Hay una acción sin confirmar. Quedó guardada en este dispositivo; comprobarla no la duplica.",style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = viewModel::retry,enabled = state.supported && state.recoveryReady && !state.busy) { Text("Comprobar acción pendiente") }
                    }
                    if(!state.loading && !state.supported) Text("Estas experiencias todavía no están habilitadas. Puedes volver a comprobarlo sin perder tu borrador.")
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
    }
}
