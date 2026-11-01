package conectate.com.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import androidx.navigation.NavController
import conectate.com.data.model.Report
import conectate.com.data.model.VideoReport
import conectate.com.data.localfallback.AdminCatalog
import conectate.com.data.localfallback.AdminBadgeOption
import conectate.com.data.localfallback.AdminModerationAppeal
import conectate.com.data.localfallback.AdminUser
import conectate.com.data.localfallback.AdminRecoveryCase
import conectate.com.data.localfallback.AdminRecoveryAccount
import conectate.com.data.localfallback.SystemHealthResponse
import conectate.com.presentation.components.FullScreenImageDialog
import coil.compose.AsyncImage
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import conectate.com.presentation.common.AppFeedbackBus

private fun recoveryDeliveryMessage(caseId: String, secret: String, contactCode: Boolean): String =
    if (contactCode) {
        "Novvin: para confirmar tu contacto nuevo en la solicitud $caseId, escribe este código de 6 dígitos en la app: $secret. " +
            "Caduca en 30 minutos. Este código no cambia tu contraseña. No respondas con claves ni documentos."
    } else {
        "Novvin: revisamos tu solicitud de recuperación. En la app toca «Ya tengo un código del administrador», " +
            "escribe $secret y crea tu contraseña nueva. El código funciona una sola vez y dejará de servir si generamos otro. " +
            "No lo compartas con nadie más. Referencia: $caseId."
    }

private fun openRecoveryMessage(context: Context, contactType: String, contact: String, message: String) {
    val intent = if (contactType == "whatsapp") {
        val digits = contact.filter(Char::isDigit)
        Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$digits&text=${Uri.encode(message)}"))
            .setPackage("com.whatsapp")
    } else {
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", contact, null)).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Novvin · recuperación de cuenta")
            putExtra(Intent.EXTRA_TEXT, message)
        }
    }
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            if (contactType != "whatsapp") throw it
            context.startActivity(intent.setPackage("com.whatsapp.w4b"))
        }
        .onFailure { AppFeedbackBus.showError("No se pudo abrir el contacto. Copia el mensaje y envíalo manualmente.") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
    navController: NavController,
    viewModel: AdminPanelViewModel = hiltViewModel()
) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    if (session == null || !viewModel.hasCurrentSession()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("Administración") }, navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("La sesión cambió. Vuelve a abrir la administración con tu cuenta actual.")
                TextButton(onClick = { navController.popBackStack() }) { Text("Volver") }
            }
        }
        return
    }
    AdminPanelContent(navController, viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminPanelContent(
    navController: NavController,
    viewModel: AdminPanelViewModel,
) {
    val context = LocalContext.current
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val adminAccess by viewModel.adminAccess.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    
    val suspendedProfiles by viewModel.suspendedProfiles.collectAsStateWithLifecycle()
    val moderationAppeals by viewModel.moderationAppeals.collectAsStateWithLifecycle()
    val recoveryCases by viewModel.recoveryCases.collectAsStateWithLifecycle()
    val recoverySearch by viewModel.recoverySearch.collectAsStateWithLifecycle()
    val recoveryFilter by viewModel.recoveryFilter.collectAsStateWithLifecycle()
    val recoveryOffset by viewModel.recoveryOffset.collectAsStateWithLifecycle()
    val recoveryTotal by viewModel.recoveryTotal.collectAsStateWithLifecycle()
    val recoveryCounts by viewModel.recoveryCounts.collectAsStateWithLifecycle()
    val recoveryPageLoading by viewModel.recoveryPageLoading.collectAsStateWithLifecycle()
    val recoveryAccounts by viewModel.recoveryAccounts.collectAsStateWithLifecycle()
    val recoverySecret by viewModel.recoverySecret.collectAsStateWithLifecycle()
    val recoverySecretCaseId by viewModel.recoverySecretCaseId.collectAsStateWithLifecycle()
    val recoverySecretAction by viewModel.recoverySecretAction.collectAsStateWithLifecycle()
    var openedRecoveryDraft by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(recoverySecret, recoverySecretCaseId, recoverySecretAction, recoveryCases) {
        val secret = recoverySecret ?: return@LaunchedEffect
        if (recoverySecretAction != "issue-code") return@LaunchedEffect
        val destination = recoveryCases.firstOrNull { it.id == recoverySecretCaseId }
            ?: return@LaunchedEffect
        val marker = secret.hashCode()
        if (openedRecoveryDraft == marker) return@LaunchedEffect
        openedRecoveryDraft = marker
        openRecoveryMessage(
            context, destination.contactType, destination.alternateContact,
            recoveryDeliveryMessage(destination.id, secret, contactCode = false),
        )
    }
    val recoveryBusy by viewModel.recoveryBusy.collectAsStateWithLifecycle()
    var expandedRecoveryCaseId by rememberSaveable { mutableStateOf<String?>(null) }
    val userReports by viewModel.userReports.collectAsStateWithLifecycle()
    val videoReports by viewModel.videoReports.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val systemHealth by viewModel.systemHealth.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val selectedUser by viewModel.selectedUser.collectAsStateWithLifecycle()
    val userSearch by viewModel.userSearch.collectAsStateWithLifecycle()
    val lastDeletedUserId by viewModel.lastDeletedUserId.collectAsStateWithLifecycle()
    val deleteCandidate by viewModel.deleteCandidate.collectAsStateWithLifecycle()

    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    var userToSuspend by remember { mutableStateOf<String?>(null) }
    var suspendReason by remember { mutableStateOf("") }
    var suspendDays by remember { mutableStateOf("7") }
    var suspendRuleCode by remember { mutableStateOf("community_standards_other") }
    var suspendSeverity by remember { mutableStateOf("high") }
    var suspendEvidence by remember { mutableStateOf("") }
    var ruleMenuExpanded by remember { mutableStateOf(false) }
    var severityMenuExpanded by remember { mutableStateOf(false) }
    var appealToReview by remember { mutableStateOf<AdminModerationAppeal?>(null) }
    var appealReviewNote by remember { mutableStateOf("") }
    // Estado para eliminar usuario completamente
    var userToDelete by remember { mutableStateOf<AdminUser?>(null) }
    var showCreateBadgeDialog by remember { mutableStateOf(false) }
    var badgeName by remember { mutableStateOf("") }
    var badgeDescription by remember { mutableStateOf("") }
    var badgeIcon by remember { mutableStateOf("🏅") }
    var badgeColor by remember { mutableStateOf("#14B8A6") }
    var badgeToDelete by remember { mutableStateOf<AdminBadgeOption?>(null) }
    val badgeIcons = remember {
        listOf("🏅", "⭐", "👑", "🛡️", "✅", "💎", "🔥", "🎬", "🎵", "🎮", "🤝", "🌟")
    }
    val badgeColors = remember {
        listOf(
            "#14B8A6", "#0EA5E9", "#22C55E", "#F59E0B",
            "#EF4444", "#EC4899", "#8B5CF6", "#64748B"
        )
    }

    LaunchedEffect(Unit) {
        viewModel.checkAdminAccess()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.startStatsAutoRefresh()
            try { awaitCancellation() }
            finally { viewModel.stopStatsAutoRefresh() }
        }
    }

    LaunchedEffect(lastDeletedUserId) {
        if (lastDeletedUserId != null && lastDeletedUserId == userToDelete?.id) {
            userToDelete = null
        }
    }

    LaunchedEffect(deleteCandidate) {
        deleteCandidate?.let { candidate ->
            userToDelete = candidate
            viewModel.consumeDeleteCandidate()
        }
    }

    if (showCreateBadgeDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isLoading) showCreateBadgeDialog = false
            },
            title = { Text("Crear insignia") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = badgeName,
                        onValueChange = { badgeName = it.take(60) },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = badgeDescription,
                        onValueChange = { badgeDescription = it.take(180) },
                        label = { Text("Descripción") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Vista previa", fontWeight = FontWeight.SemiBold)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = badgeColorValue(badgeColor).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, badgeColorValue(badgeColor)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                badgeIcon.ifBlank { "🏅" },
                                fontSize = 28.sp
                            )
                            Column {
                                Text(
                                    badgeName.ifBlank { "Nombre de la insignia" },
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColorValue(badgeColor)
                                )
                                Text(
                                    badgeDescription.ifBlank { "Así se verá en el perfil." },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Text("Elige un icono", fontWeight = FontWeight.SemiBold)
                    badgeIcons.chunked(4).forEach { rowIcons ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowIcons.forEach { icon ->
                                FilterChip(
                                    selected = badgeIcon == icon,
                                    onClick = { badgeIcon = icon },
                                    label = { Text(icon, fontSize = 20.sp) }
                                )
                            }
                        }
                    }
                    Text("Elige un color", fontWeight = FontWeight.SemiBold)
                    badgeColors.chunked(4).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            rowColors.forEach { color ->
                                val selected = badgeColor.equals(color, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(badgeColorValue(color))
                                        .border(
                                            width = if (selected) 4.dp else 1.dp,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                            shape = CircleShape
                                        )
                                        .clickable { badgeColor = color },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Color seleccionado",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = badgeColor,
                        onValueChange = { badgeColor = it.take(7).uppercase() },
                        label = { Text("Color personalizado (opcional)") },
                        supportingText = { Text("Puedes elegirlo arriba; este campo es solo avanzado.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !isLoading &&
                        badgeName.trim().length >= 2 &&
                        Regex("^#[0-9A-Fa-f]{6}$").matches(badgeColor.trim()),
                    onClick = {
                        viewModel.createBadge(
                            name = badgeName.trim(),
                            description = badgeDescription.trim(),
                            icon = badgeIcon.trim().ifBlank { "🏅" },
                            color = badgeColor.trim()
                        )
                        showCreateBadgeDialog = false
                        badgeName = ""
                        badgeDescription = ""
                        badgeIcon = "🏅"
                        badgeColor = "#14B8A6"
                    }
                ) { Text("Crear") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isLoading,
                    onClick = { showCreateBadgeDialog = false }
                ) { Text("Cancelar") }
            }
        )
    }

    badgeToDelete?.let { badge ->
        AlertDialog(
            onDismissRequest = {
                if (!isLoading) badgeToDelete = null
            },
            title = { Text("Eliminar insignia") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = badgeColorValue(badge.color).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, badgeColorValue(badge.color))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(badge.icon.ifBlank { "🏅" }, fontSize = 26.sp)
                            Text(badge.name, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("También se quitará esta insignia de los perfiles que la tengan asignada.")
                }
            },
            confirmButton = {
                Button(
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        viewModel.deleteBadge(badge.id)
                        badgeToDelete = null
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isLoading,
                    onClick = { badgeToDelete = null }
                ) { Text("Cancelar") }
            }
        )
    }

    if (userToSuspend != null) {
        AlertDialog(
            onDismissRequest = { userToSuspend = null },
            title = { Text("Suspender Usuario") },
            text = {
                val rules = listOf(
                    "harassment_abuse" to "Acoso o abuso",
                    "fraud_scam" to "Fraude o estafa",
                    "hate_discrimination" to "Odio o discriminación",
                    "sexual_content" to "Contenido sexual no permitido",
                    "violence_threats" to "Violencia o amenazas",
                    "spam_manipulation" to "Spam o manipulación",
                    "privacy_impersonation" to "Privacidad o suplantación",
                    "community_standards_other" to "Otra infracción"
                )
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("ID: ${userToSuspend}")
                    ExposedDropdownMenuBox(
                        expanded = ruleMenuExpanded,
                        onExpandedChange = { ruleMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = rules.firstOrNull { it.first == suspendRuleCode }?.second.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Regla aplicada") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ruleMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = ruleMenuExpanded,
                            onDismissRequest = { ruleMenuExpanded = false }
                        ) {
                            rules.forEach { (code, title) ->
                                DropdownMenuItem(
                                    text = { Text(title) },
                                    onClick = {
                                        suspendRuleCode = code
                                        ruleMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = suspendReason,
                        onValueChange = { suspendReason = it.take(500) },
                        label = { Text("Explicación para el usuario") },
                        supportingText = { Text("Debe describir qué ocurrió; se guardará en auditoría") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = suspendEvidence,
                        onValueChange = { suspendEvidence = it.take(1000) },
                        label = { Text("Evidencias resumidas") },
                        supportingText = { Text("Una por línea; no incluyas datos privados innecesarios") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ExposedDropdownMenuBox(
                        expanded = severityMenuExpanded,
                        onExpandedChange = { severityMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = when (suspendSeverity) {
                                "critical" -> "Crítica"
                                "medium" -> "Media"
                                "low" -> "Baja"
                                else -> "Alta"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Severidad") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(severityMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = severityMenuExpanded,
                            onDismissRequest = { severityMenuExpanded = false }
                        ) {
                            listOf(
                                "low" to "Baja",
                                "medium" to "Media",
                                "high" to "Alta",
                                "critical" to "Crítica"
                            ).forEach { (code, title) ->
                                DropdownMenuItem(
                                    text = { Text(title) },
                                    onClick = {
                                        suspendSeverity = code
                                        severityMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = suspendDays,
                        onValueChange = { value ->
                            if (value.length <= 4 && value.all(Char::isDigit)) suspendDays = value
                        },
                        label = { Text("Días (0 para permanente)") },
                        supportingText = { Text("Entre 0 y 3650 días") }
                    )
                }
            },
            confirmButton = {
                val days = suspendDays.toIntOrNull()
                Button(
                    enabled = suspendReason.isNotBlank() && days != null && days in 0..3650,
                    onClick = {
                        viewModel.suspendUser(
                            userToSuspend!!,
                            suspendReason.trim(),
                            days ?: return@Button,
                            suspendRuleCode,
                            suspendSeverity,
                            suspendEvidence.lines().map { it.trim() }.filter { it.isNotBlank() }
                        )
                        userToSuspend = null
                        suspendReason = ""
                        suspendDays = "7"
                        suspendRuleCode = "community_standards_other"
                        suspendSeverity = "high"
                        suspendEvidence = ""
                    }
                ) { Text("Suspender") }
            },
            dismissButton = {
                TextButton(onClick = { userToSuspend = null }) { Text("Cancelar") }
            }
        )
    }

    appealToReview?.let { appeal ->
        AlertDialog(
            onDismissRequest = {
                if (!isLoading) {
                    appealToReview = null
                    appealReviewNote = ""
                }
            },
            title = { Text("Revisión humana de apelación") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(appeal.displayName, fontWeight = FontWeight.Bold)
                    Text("Regla: ${appeal.ruleTitle}")
                    Text("Decisión: ${appeal.explanation}")
                    Text("Apelación:", fontWeight = FontWeight.SemiBold)
                    Text(appeal.statement)
                    OutlinedTextField(
                        value = appealReviewNote,
                        onValueChange = { appealReviewNote = it.take(2000) },
                        label = { Text("Fundamento de la revisión") },
                        supportingText = { Text("Será visible para la persona") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !isLoading && appealReviewNote.trim().length >= 3,
                    onClick = {
                        viewModel.reviewModerationAppeal(
                            appeal.id,
                            "overturned",
                            appealReviewNote
                        )
                        appealToReview = null
                        appealReviewNote = ""
                    }
                ) { Text("Aceptar apelación") }
            },
            dismissButton = {
                Column {
                    TextButton(
                        enabled = !isLoading && appealReviewNote.trim().length >= 3,
                        onClick = {
                            viewModel.reviewModerationAppeal(
                                appeal.id,
                                "upheld",
                                appealReviewNote
                            )
                            appealToReview = null
                            appealReviewNote = ""
                        }
                    ) { Text("Mantener decisión") }
                    TextButton(
                        enabled = !isLoading,
                        onClick = {
                            appealToReview = null
                            appealReviewNote = ""
                        }
                    ) { Text("Cancelar") }
                }
            }
        )
    }

    // Dialog de confirmacion para ELIMINAR usuario completamente (accion IRREVERSIBLE)
    userToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!isLoading) {
                    userToDelete = null
                }
            },
            title = {
                Text(
                    "¿Eliminar este usuario?",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        "Esta acción es irreversible.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Se eliminarán la cuenta de ${target.displayName} y todos sus datos relacionados del servidor, autenticación, base de datos y almacenamiento.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (target.isTestAccount) "Cuenta ficticia: ${target.displayName}"
                        else "Cuenta suspendida: ${target.displayName}",
                        fontWeight = FontWeight.Bold
                    )
                    if (!target.isTestAccount) {
                        Text(
                            "Una cuenta real solo puede eliminarse mientras siga suspendida.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isLoading && (target.isTestAccount || target.isSuspended),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        viewModel.deleteUserCompletely(target.id)
                    }
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text("Sí, eliminar", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isLoading,
                    onClick = { userToDelete = null }
                ) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Panel de Administrador") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                    }
                }
            )
        }
    ) { padding ->
        if (!isAdmin && !isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Acceso denegado", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                    Text("No tienes permisos de administrador.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (isAdmin || isLoading) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = selectedTabIndex) {
                Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("Resumen") })
                Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text("Usuarios") })
                Tab(selected = selectedTabIndex == 2, onClick = { selectedTabIndex = 2 }, text = { Text("Suspendidos") })
                Tab(selected = selectedTabIndex == 3, onClick = { selectedTabIndex = 3 }, text = { Text("Reportes") })
                Tab(selected = selectedTabIndex == 4, onClick = { selectedTabIndex = 4 }, text = { Text("Videos") })
                Tab(selected = selectedTabIndex == 5, onClick = { selectedTabIndex = 5 }, text = { Text("Apelaciones") })
                Tab(selected = selectedTabIndex == 6, onClick = { selectedTabIndex = 6; viewModel.refreshRecoveryCases() }, text = { Text("Recuperaciones (${(recoveryCounts["pending"] ?: 0) + (recoveryCounts["in_review"] ?: 0)})") })
                if ("admin.system.read" in adminAccess.capabilities) Tab(selected = selectedTabIndex == 7, onClick = { selectedTabIndex = 7 }, text = { Text("Avisos") })
            }

            if (!errorMessage.isNullOrBlank()) {
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(errorMessage ?: "", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (!statusMessage.isNullOrBlank()) {
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(statusMessage ?: "", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            if (isAdmin && !adminAccess.canWrite) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(
                        "Acceso de solo lectura: puedes consultar el panel, pero no modificar datos.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            val adminListState = key(selectedTabIndex) { rememberLazyListState() }
            LazyColumn(state = adminListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (selectedTabIndex) {
                    7 -> item(key = "system-announcements") {
                        Column { SystemAnnouncementAdminSection(adminAccess) }
                    }
                    0 -> {
                        item(key = "admin-summary") { AdminSummary(stats, systemHealth, isLoading) }
                    }
                    1 -> {
                        item(key = "admin-create-badge") {
                            OutlinedButton(
                                onClick = { showCreateBadgeDialog = true },
                                enabled = adminAccess.canWrite,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Crear insignia")
                            }
                        }
                        val customBadges = catalog.badges.filter { it.id.startsWith("custom-") }
                        if (customBadges.isNotEmpty()) {
                            item(key = "admin-custom-badges") {
                                CustomBadgesCard(
                                    badges = customBadges,
                                    onDelete = { badgeToDelete = it }
                                )
                            }
                        }
                        item(key = "admin-user-search") {
                            AdminUserSearch(
                                query = userSearch,
                                onQueryChange = viewModel::setUserSearch,
                                onSearch = viewModel::searchUsers
                            )
                        }
                        if (users.isEmpty() && !isLoading) item(key = "admin-users-empty") { Text("No se encontraron usuarios.") }
                        items(users, key = { "admin-user:${it.id}" }) { summary ->
                            val user = selectedUser?.takeIf { it.id == summary.id } ?: summary
                            AdminUserCard(
                                user = user,
                                catalog = catalog,
                                canWrite = adminAccess.canWrite,
                                canManageAccess = adminAccess.isOwner,
                                canDelete = adminAccess.canDeleteUsers,
                                expanded = selectedUser?.id == summary.id,
                                onOpen = { viewModel.toggleUserDetails(summary.id) },
                                onVerifiedChange = { viewModel.updateUserVerification(summary.id, it) },
                                onBadgeChange = { badgeId, enabled ->
                                    viewModel.updateUserBadge(summary.id, badgeId, enabled)
                                },
                                onFrameChange = { viewModel.updateUserFrame(summary.id, it) },
                                onAccessChange = { viewModel.updateUserAdminAccess(summary.id, it) },
                                onSuspend = { userToSuspend = summary.id },
                                onReactivate = { viewModel.unsuspendUser(summary.id) },
                                onDelete = { viewModel.prepareUserDeletion(user.id) }
                            )
                        }
                    }
                    2 -> {
                        if (suspendedProfiles.isEmpty() && !isLoading) {
                            item(key = "admin-suspended-empty") { Text("No hay usuarios suspendidos.") }
                        }
                        items(suspendedProfiles, key = { "admin-suspended:${it.userId}" }) { profile ->
                            SuspendedProfileCard(
                                profile = profile,
                                canWrite = adminAccess.canWrite,
                                canDelete = adminAccess.canDeleteUsers,
                                onReactivate = { viewModel.unsuspendUser(profile.userId) },
                                onDelete = { viewModel.prepareUserDeletion(profile.userId) }
                            )
                        }
                    }
                    3 -> {
                        if (userReports.isEmpty() && !isLoading) {
                            item(key = "admin-reports-empty") { Text("No hay reportes.") }
                        }
                        itemsIndexed(
                            items = userReports,
                            key = { index, report -> "admin-report:${report.id ?: index}" }
                        ) { _, report ->
                            UserReportCard(
                                report = report,
                                canWrite = adminAccess.canWrite,
                                onSuspend = { userToSuspend = report.reported_user_id },
                                onStatusChange = { status -> viewModel.updateReportStatus(report.id ?: "", status, "user") }
                            )
                        }
                    }
                    4 -> {
                        if (videoReports.isEmpty() && !isLoading) {
                            item(key = "admin-video-reports-empty") { Text("No hay reportes de videos.") }
                        }
                        items(videoReports, key = { "admin-video-report:${it.id}" }) { report ->
                            VideoReportCard(
                                report = report,
                                canWrite = adminAccess.canWrite,
                                onSuspend = { userToSuspend = report.videoOwnerId },
                                onStatusChange = { status -> viewModel.updateReportStatus(report.id, status, "video") }
                            )
                        }
                    }
                    5 -> {
                        if (moderationAppeals.isEmpty() && !isLoading) {
                            item(key = "admin-appeals-empty") { Text("No hay apelaciones para revisar.") }
                        }
                        items(moderationAppeals, key = { "admin-appeal:${it.id}" }) { appeal ->
                            ModerationAppealCard(
                                appeal = appeal,
                                canWrite = adminAccess.canWrite,
                                onReview = {
                                    appealReviewNote = appeal.reviewerNote.orEmpty()
                                    appealToReview = appeal
                                }
                            )
                        }
                    }
                    6 -> {
                        item(key = "recovery-guide") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Recuperación de cuentas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "Revisa la solicitud, confirma la cuenta y entrega el código al contacto indicado.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = { viewModel.refreshRecoveryCases() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text("Actualizar solicitudes") }
                                recoverySecret?.let { secret ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                                    ) {
                                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(
                                                "Código listo para entregar",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            )
                                            val matched = recoveryCases.firstOrNull { it.id == recoverySecretCaseId }
                                            Text(
                                                "Solicitud #${recoverySecretCaseId.orEmpty().take(8)} · ${matched?.alternateContact ?: "Comprueba el destinatario"}",
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                            ) {
                                                androidx.compose.foundation.text.selection.SelectionContainer {
                                                    Text(
                                                        secret,
                                                        modifier = Modifier.padding(14.dp),
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                }
                                            }
                                            Text(
                                                "Revisa el destinatario y envía el mensaje desde WhatsApp o correo. El código se muestra una sola vez; uno nuevo anula el anterior.",
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            val message = recoveryDeliveryMessage(
                                                recoverySecretCaseId.orEmpty(), secret,
                                                recoverySecretAction == "contact-code",
                                            )
                                            matched?.let { destination ->
                                                Button(onClick = {
                                                    openRecoveryMessage(context, destination.contactType, destination.alternateContact, message)
                                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                                    Text(if (destination.contactType == "whatsapp") "Abrir WhatsApp" else "Preparar correo")
                                                }
                                            }
                                            OutlinedButton(onClick = {
                                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                                    ?.setPrimaryClip(ClipData.newPlainText("Mensaje de recuperación Novvin", message))
                                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copiar mensaje completo") }
                                            OutlinedButton(onClick = {
                                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                                    ?.setPrimaryClip(ClipData.newPlainText("Código de recuperación Novvin", secret))
                                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copiar solo el código") }
                                            TextButton(onClick = viewModel::clearRecoverySecret) { Text("Cerrar código") }
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "recovery-controls") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = recoverySearch, onValueChange = viewModel::setRecoverySearch,
                                    label = { Text("Buscar solicitud, cuenta o contacto") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth())
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("pending" to "Pendientes", "in_review" to "En revisión", "issued" to "Código emitido",
                                        "completed" to "Completadas", "rejected" to "Rechazadas", "all" to "Todas").forEach { (value, label) ->
                                        FilterChip(selected = recoveryFilter == value, onClick = {
                                            expandedRecoveryCaseId = null; viewModel.setRecoveryFilter(value)
                                        }, label = { Text("$label (${if (value == "all") recoveryCounts.values.sum() else recoveryCounts[value] ?: 0})") })
                                    }
                                }
                                Text(if (recoveryTotal == 0) "0 solicitudes" else
                                    "${recoveryOffset + 1}–${minOf(recoveryOffset + recoveryCases.size, recoveryTotal)} de $recoveryTotal · Antiguas primero")
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    OutlinedButton(onClick = { expandedRecoveryCaseId = null; viewModel.setRecoveryPage(recoveryOffset - 20) },
                                        enabled = recoveryOffset > 0 && !recoveryPageLoading) { Text("Anterior") }
                                    OutlinedButton(onClick = { expandedRecoveryCaseId = null; viewModel.setRecoveryPage(recoveryOffset + 20) },
                                        enabled = recoveryOffset + 20 < recoveryTotal && !recoveryPageLoading) { Text("Siguiente") }
                                }
                                if (recoveryPageLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                        if (recoveryCases.isEmpty() && !recoveryPageLoading) item(key = "recovery-empty") { Text("No hay solicitudes de recuperación.") }
                        items(recoveryCases, key = { "recovery:${it.id}" }) { recovery ->
                            AdminRecoveryCard(
                                recovery, recoveryAccounts, adminAccess.isOwner || "admin.access.manage" in adminAccess.capabilities, recoveryBusy || recoveryPageLoading,
                                expanded = expandedRecoveryCaseId == recovery.id,
                                onToggle = {
                                    expandedRecoveryCaseId = if (expandedRecoveryCaseId == recovery.id) null else recovery.id
                                    viewModel.clearRecoveryAccounts()
                                    if (expandedRecoveryCaseId == recovery.id && recovery.identifier.trim().length >= 3) {
                                        viewModel.findRecoveryAccounts(recovery.identifier)
                                    }
                                },
                                onLoadEvidence = { evidenceId -> viewModel.downloadRecoveryEvidence(recovery.id, evidenceId) },
                                onSearch = viewModel::findRecoveryAccounts,
                                onAction = { action, account, evidence, resetTwoFactor ->
                                    viewModel.recoveryAction(recovery.id, action, account, evidence, resetTwoFactor)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun AdminRecoveryCard(
    recovery: AdminRecoveryCase,
    accounts: List<AdminRecoveryAccount>,
    canReview: Boolean,
    busy: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSearch: (String) -> Unit,
    onLoadEvidence: suspend (String) -> Result<ByteArray>,
    onAction: (String, String, String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var accountQuery by remember(recovery.id) { mutableStateOf(recovery.identifier) }
    var searchedQuery by remember(recovery.id) { mutableStateOf(recovery.identifier) }
    var accountId by remember(recovery.id) { mutableStateOf("") }
    var evidence by remember(recovery.id) { mutableStateOf("") }
    var resetTwoFactor by remember(recovery.id) { mutableStateOf(false) }
    var confirmIssue by remember(recovery.id) { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            accountQuery = recovery.identifier
            searchedQuery = recovery.identifier
            accountId = ""
        }
    }
    val statusLabel = when (recovery.status) {
        "pending" -> "Pendiente"
        "in_review" -> "En revisión"
        "issued" -> "Código emitido"
        "completed" -> "Completada"
        "rejected" -> "Rechazada"
        else -> recovery.status
    }
    val statusColor = when (recovery.status) {
        "completed" -> MaterialTheme.colorScheme.primaryContainer
        "rejected" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val statusTextColor = when (recovery.status) {
        "completed" -> MaterialTheme.colorScheme.onPrimaryContainer
        "rejected" -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    if (confirmIssue) {
        AlertDialog(
            onDismissRequest = { confirmIssue = false },
            title = { Text("Emitir código de recuperación") },
            text = {
                val selected = accounts.firstOrNull { it.id == accountId }
                Text(
                    "Cuenta: ${selected?.displayName.orEmpty()} · ${selected?.email.orEmpty()}\n" +
                        "Enviar a: ${recovery.alternateContact}\n\n" +
                        "La contraseña cambiará cuando la persona use el código. " +
                        "Si ya había uno, quedará anulado." +
                        if (resetTwoFactor) " También se restablecerá el segundo factor." else ""
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmIssue = false
                    onAction("issue-code", accountId.trim(), evidence.trim(), resetTwoFactor)
                }) { Text("Confirmar y preparar mensaje") }
            },
            dismissButton = { TextButton(onClick = { confirmIssue = false }) { Text("Cancelar") } },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(50), color = statusColor) {
                Text(
                    statusLabel,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusTextColor,
                )
            }
            Text(
                recovery.candidateName.ifBlank { recovery.identifier },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Solicitud #${recovery.id.take(8)} · ${recovery.createdAt.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Enviar código por ${if (recovery.contactType == "whatsapp") "WhatsApp" else "correo"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(recovery.alternateContact, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            FilledTonalButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (expanded) "Ocultar detalles" else "Revisar solicitud") }
            if (expanded) {
                if (recovery.reviewedAt.isNotBlank()) Text("Última revisión: ${recovery.reviewedAt.take(16).replace('T', ' ')}",
                    style = MaterialTheme.typography.bodySmall)
                if (canReview && recovery.status in setOf("pending", "in_review")) {
                    OutlinedButton(onClick = { onAction("review", if (recovery.status == "pending") "in_review" else "pending", "", false) },
                        enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (recovery.status == "pending") "Marcar en revisión" else "Volver a pendientes")
                    }
                }
                OutlinedButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("Contacto recuperación Novvin", recovery.alternateContact))
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copiar contacto") }
                HorizontalDivider()
                Text("Lo que cuenta la persona", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(recovery.description, style = MaterialTheme.typography.bodyMedium)
                RecoveryEvidenceSection(recovery.evidence, onLoadEvidence)
                if (recovery.profileHint.isNotBlank()) Text(
                    "Perfil recordado: ${recovery.profileHint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recovery.status in setOf("pending", "in_review", "issued") && canReview) {
                    HorizontalDivider()
                    Text("1 · Confirma la cuenta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (recovery.candidateUserId.isNotBlank()) Text(
                        "Coincidencia sugerida: ${recovery.candidateName} · ${recovery.candidateEmail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) else Text("No encontramos una coincidencia clara. Busca la cuenta antes de continuar.", color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(
                        value = accountQuery, onValueChange = { accountQuery = it; accountId = "" },
                        label = { Text("Buscar por correo, nombre o usuario") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    FilledTonalButton(
                        onClick = { accountId = ""; searchedQuery = accountQuery.trim(); onSearch(searchedQuery) },
                        enabled = accountQuery.trim().length >= 3,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Buscar cuentas") }
                    val visibleAccounts = if (accountQuery.trim() == searchedQuery) accounts else emptyList()
                    if (visibleAccounts.isEmpty()) Text("Busca y selecciona la cuenta correcta para continuar.", style = MaterialTheme.typography.bodySmall)
                    visibleAccounts.forEach { account ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { accountId = account.id },
                            shape = RoundedCornerShape(12.dp),
                            color = if (accountId == account.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = accountId == account.id, onClick = { accountId = account.id })
                                Column(Modifier.weight(1f)) {
                                    Text(account.displayName.ifBlank { account.username }, fontWeight = FontWeight.SemiBold)
                                    Text(account.email, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("2 · Anota cómo la verificaste", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = evidence, onValueChange = { evidence = it.take(240) },
                        label = { Text("Comprobación o motivo de rechazo") },
                        supportingText = { Text("Mínimo 20 caracteres (${evidence.trim().length}/20). No escribas contraseñas ni documentos.") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { resetTwoFactor = !resetTwoFactor },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = resetTwoFactor, onCheckedChange = { resetTwoFactor = it })
                        Text("Restablecer también el segundo factor", modifier = Modifier.weight(1f))
                    }
                    Text("3 · Entrega el código", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Se preparará un mensaje para el contacto indicado. Revísalo antes de enviarlo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { confirmIssue = true },
                        enabled = !busy && visibleAccounts.any { it.id == accountId } && evidence.trim().length >= 20,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(if (recovery.status == "issued") "Generar un código nuevo" else "Aprobar y preparar mensaje") }
                    if (recovery.status == "issued") Text(
                        "Un código nuevo anula el anterior.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (recovery.status in setOf("pending", "in_review", "issued")) {
                        OutlinedButton(
                            onClick = { onAction("reject", "", evidence.trim(), false) },
                            enabled = !busy && evidence.trim().length >= 20,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Rechazar solicitud") }
                    }
                } else if (!canReview && recovery.status in setOf("pending", "in_review", "issued")) {
                    Text("Tu acceso permite consultar solicitudes. Necesitas permiso de gestión para revisarlas.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun badgeColorValue(value: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(Color(0xFF14B8A6))

@Composable
private fun CustomBadgesCard(
    badges: List<AdminBadgeOption>,
    onDelete: (AdminBadgeOption) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Insignias personalizadas", fontWeight = FontWeight.Bold)
                Text(
                    "Previsualiza y administra las insignias creadas en el panel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            badges.forEachIndexed { index, badge ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = badgeColorValue(badge.color).copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, badgeColorValue(badge.color)),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(badge.icon.ifBlank { "🏅" }, fontSize = 23.sp)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            badge.name,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeColorValue(badge.color)
                        )
                        if (badge.description.isNotBlank()) {
                            Text(
                                badge.description,
                                maxLines = 2,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (badge.canDelete) {
                        IconButton(onClick = { onDelete(badge) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Eliminar ${badge.name}",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Creada por otro administrador",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                if (index < badges.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AdminSummary(
    stats: conectate.com.data.localfallback.AdminDashboardStats,
    systemHealth: SystemHealthResponse?,
    isLoading: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SystemHealthPanel(systemHealth, isLoading)
        Spacer(Modifier.height(8.dp))
        Text("Estado de moderación", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        AdminMetric("Usuarios", stats.totalUsers, Icons.Default.People)
        AdminMetric("Videos publicados", stats.totalVideos, Icons.Default.VideoLibrary)
        AdminMetric("Cuentas suspendidas", stats.suspendedUsers, Icons.Default.Block)
        AdminMetric("Reportes de usuarios pendientes", stats.pendingUserReports, Icons.Default.Report)
        AdminMetric("Reportes de videos pendientes", stats.pendingVideoReports, Icons.Default.Flag)
    }
}

@Composable
private fun SystemHealthPanel(health: SystemHealthResponse?, isLoading: Boolean) {
    val labels = linkedMapOf(
        "api" to "API",
        "database" to "Base de datos",
        "storage" to "Almacenamiento",
        "realtime" to "Mensajes en tiempo real",
        "voice" to "Voz y llamadas",
        "live" to "Directos",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Salud de Novvin",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        if (health == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        if (isLoading) "Comprobando servicios…"
                        else "No se pudo comprobar el estado de los servicios. Pulsa actualizar para intentarlo otra vez.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (health != null) {
        val overallColor = if (health.ok) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error
        Text(
            if (health.ok) "Todos los servicios responden correctamente."
            else "Uno o más servicios necesitan atención.",
            color = overallColor,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Servidor ${health.release.release} · commit ${health.release.commit.take(12)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        labels.forEach { (key, label) ->
            val component = health.components[key]
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (component?.ok == true) Icons.Default.CheckCircle
                        else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (component?.ok == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, fontWeight = FontWeight.SemiBold)
                        if (!component?.message.isNullOrBlank()) {
                            Text(
                                component?.message.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        when {
                            component == null -> "Sin datos"
                            component.ok -> "${component.latencyMs} ms"
                            component.status == "turn_not_configured" -> "Sin TURN"
                            component.status == "timeout" -> "Sin respuesta"
                            else -> "No disponible"
                        },
                        color = if (component?.ok == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun AdminMetric(label: String, value: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AdminUserSearch(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nombre, correo, usuario o ID") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
                Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Text("Buscar") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Nombre, correo, usuario o ID") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSearch) { Text("Buscar") }
            }
        }
    }
}

@Composable
private fun AdminUserCard(
    user: AdminUser,
    catalog: AdminCatalog,
    canWrite: Boolean,
    canManageAccess: Boolean,
    canDelete: Boolean,
    expanded: Boolean,
    onOpen: () -> Unit,
    onVerifiedChange: (Boolean) -> Unit,
    onBadgeChange: (String, Boolean) -> Unit,
    onFrameChange: (String) -> Unit,
    onAccessChange: (String) -> Unit,
    onSuspend: () -> Unit,
    onReactivate: () -> Unit,
    onDelete: () -> Unit
) {
    var frameMenuExpanded by remember(user.id) { mutableStateOf(false) }
    var accessMenuExpanded by remember(user.id) { mutableStateOf(false) }
    val badgeIds = user.badges.map { it.id }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    conectate.com.presentation.components.CircularProfileImage(
                        imageUrl = user.avatarUrl,
                        ownerId = user.id,
                        fallbackName = user.displayName,
                        size = 52.dp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(user.displayName, fontWeight = FontWeight.Bold)
                    Text(user.email, style = MaterialTheme.typography.bodySmall)
                    if (user.username.isNotBlank()) Text("@${user.username}", style = MaterialTheme.typography.bodySmall)
                }
                if (user.isTestAccount) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Text(
                            "PRUEBA",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Ocultar detalles" else "Ver detalles")
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text("ID: ${user.id}", style = MaterialTheme.typography.bodySmall)
                Text("Origen: ${user.syncSource} · Alta: ${user.createdAt ?: "Sin fecha"}", style = MaterialTheme.typography.bodySmall)
                Text(
                    when {
                        user.adminIsOwner -> "Administrador propietario"
                        user.adminAccessLevel == "full" -> "Administrador delegado · acceso total sin eliminación"
                        user.adminAccessLevel == "write" -> "Administrador delegado · moderación"
                        user.adminAccessLevel == "view" -> "Administrador delegado · solo lectura"
                        else -> "Usuario sin acceso administrativo"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (user.isAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                user.content?.let { content ->
                    Text(
                        "Contenido: ${content.posts} publicaciones, ${content.videos} videos, ${content.moments} momentos, ${content.storageObjects} archivos",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Verificación", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = user.isVerified,
                        enabled = canWrite,
                        onCheckedChange = onVerifiedChange
                    )
                }

                Text("Insignias", fontWeight = FontWeight.SemiBold)
                catalog.badges.forEach { badge ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = canWrite) {
                            onBadgeChange(badge.id, badge.id !in badgeIds)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = badge.id in badgeIds,
                            onCheckedChange = null
                        )
                        Text("${badge.icon} ${badge.name}")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Marco", fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(enabled = canWrite, onClick = { frameMenuExpanded = true }) {
                        val frameName = catalog.frames.firstOrNull { it.id == user.profileFrame }?.name ?: "Sin marco"
                        Text(frameName)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = frameMenuExpanded, onDismissRequest = { frameMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Sin marco") },
                            onClick = { frameMenuExpanded = false; onFrameChange("") }
                        )
                        catalog.frames.forEach { frame ->
                            DropdownMenuItem(
                                text = { Text(frame.name) },
                                onClick = { frameMenuExpanded = false; onFrameChange(frame.id) }
                            )
                        }
                    }
                }

                if (canManageAccess) {
                    Spacer(Modifier.height(8.dp))
                    Text("Acceso administrativo", fontWeight = FontWeight.SemiBold)
                    if (user.adminIsOwner) {
                        Text(
                            "Propietario protegido: este acceso no puede retirarse desde el panel.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Box {
                            OutlinedButton(
                                onClick = { accessMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    when (user.adminAccessLevel) {
                                        "view" -> "Solo lectura"
                                        "write" -> "Lectura y moderación"
                                        "full" -> "Total sin eliminar cuentas"
                                        else -> "Sin acceso"
                                    }
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = accessMenuExpanded,
                                onDismissRequest = { accessMenuExpanded = false }
                            ) {
                                listOf(
                                    "none" to "Sin acceso",
                                    "view" to "Solo lectura",
                                    "write" to "Lectura y moderación",
                                    "full" to "Total sin eliminar cuentas"
                                ).forEach { (level, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            accessMenuExpanded = false
                                            onAccessChange(level)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!user.isAdmin) {
                        if (user.isSuspended) {
                            OutlinedButton(enabled = canWrite, onClick = onReactivate, modifier = Modifier.fillMaxWidth()) { Text("Reactivar") }
                        } else {
                            OutlinedButton(enabled = canWrite, onClick = onSuspend, modifier = Modifier.fillMaxWidth()) { Text("Suspender") }
                        }
                    }
                    if (user.isTestAccount || user.isSuspended) {
                        Button(
                            onClick = onDelete,
                            enabled = canDelete,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text(if (user.isTestAccount) "Eliminar prueba" else "Eliminar cuenta", color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModerationAppealCard(
    appeal: AdminModerationAppeal,
    canWrite: Boolean,
    onReview: () -> Unit
) {
    val pending = appeal.status == "pending" || appeal.status == "reviewing"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(appeal.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (appeal.email.isNotBlank()) {
                Text(appeal.email, style = MaterialTheme.typography.bodySmall)
            }
            Text("Regla: ${appeal.ruleTitle}", fontWeight = FontWeight.SemiBold)
            Text("Decisión: ${appeal.explanation}")
            HorizontalDivider()
            Text("Apelación", fontWeight = FontWeight.SemiBold)
            Text(appeal.statement)
            Text(
                "Estado: ${when (appeal.status) {
                    "pending" -> "Pendiente"
                    "reviewing" -> "En revisión"
                    "upheld" -> "Decisión mantenida"
                    "overturned" -> "Aceptada"
                    else -> appeal.status
                }}",
                color = if (pending) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary
            )
            appeal.reviewerNote?.takeIf { it.isNotBlank() }?.let {
                Text("Fundamento del revisor: $it")
            }
            if (pending) {
                Button(enabled = canWrite, onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.RateReview, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Revisar apelación")
                }
            }
        }
    }
}

@Composable
private fun SuspendedProfileCard(
    profile: SuspendedProfile,
    canWrite: Boolean,
    canDelete: Boolean,
    onReactivate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("ID: ${profile.userId}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            profile.ruleTitle?.takeIf { it.isNotBlank() }?.let {
                Text("Regla: $it", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (!profile.suspensionReason.isNullOrBlank()) {
                Text("Razón: ${profile.suspensionReason}", style = MaterialTheme.typography.bodySmall)
            }
            profile.explanation
                ?.takeIf { it.isNotBlank() && it != profile.suspensionReason }
                ?.let { Text("Explicación: $it", style = MaterialTheme.typography.bodySmall) }
            if (profile.evidence.isNotEmpty()) {
                Text("Evidencia:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                profile.evidence.take(5).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            profile.appealStatus?.let {
                Text(
                    "Apelación: ${when (it) {
                        "pending" -> "pendiente"
                        "reviewing" -> "en revisión"
                        "upheld" -> "decisión mantenida"
                        "overturned" -> "aceptada"
                        else -> it
                    }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Text("Hasta: ${profile.suspendedUntil ?: "Permanente"}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = canWrite, onClick = onReactivate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reactivar")
                }
                Button(
                    onClick = onDelete,
                    enabled = canDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Eliminar", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun UserReportCard(
    report: Report,
    canWrite: Boolean,
    onSuspend: () -> Unit,
    onStatusChange: (String) -> Unit
) {
    var showEvidence by remember(report.evidence_url) { mutableStateOf(false) }
    if (showEvidence && !report.evidence_url.isNullOrBlank()) {
        FullScreenImageDialog(
            imageUrl = report.evidence_url,
            onDismiss = { showEvidence = false }
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val typeLabel = when (report.contentType?.lowercase()) {
                "post" -> "Publicación"
                "comment" -> "Comentario"
                else -> "Usuario"
            }
            Text("$typeLabel reportado", fontWeight = FontWeight.Bold)
            Text("Usuario: ${report.reported_user_name} (${report.reported_user_id})")
            if (!report.contentId.isNullOrBlank()) {
                Text("Contenido ID: ${report.contentId}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Por: ${report.reporter_name}")
            Text("Razón: ${report.reason}")
            if (!report.description.isNullOrBlank()) {
                Text("Detalle: ${report.description}", style = MaterialTheme.typography.bodySmall)
            }
            if (!report.evidence_url.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Evidencia", style = MaterialTheme.typography.labelLarge)
                AsyncImage(
                    model = report.evidence_url,
                    contentDescription = "Evidencia del reporte",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showEvidence = true },
                    contentScale = ContentScale.Fit
                )
            }
            Text("Estado: ${report.status}", color = if (report.status == "pendiente") Color.Red else Color.Green)
            
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (maxWidth < 430.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = canWrite, onClick = onSuspend, modifier = Modifier.fillMaxWidth()) { Text("Suspender") }
                        if (report.status == "pendiente") {
                            OutlinedButton(
                                onClick = { onStatusChange("resuelto") },
                                enabled = canWrite,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Resolver") }
                            OutlinedButton(
                                onClick = { onStatusChange("ignorado") },
                                enabled = canWrite,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Ignorar") }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = canWrite, onClick = onSuspend) { Text("Suspender") }
                        if (report.status == "pendiente") {
                            OutlinedButton(enabled = canWrite, onClick = { onStatusChange("resuelto") }) { Text("Resolver") }
                            OutlinedButton(enabled = canWrite, onClick = { onStatusChange("ignorado") }) { Text("Ignorar") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoReportCard(
    report: VideoReport,
    canWrite: Boolean,
    onSuspend: () -> Unit,
    onStatusChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Video ID: ${report.videoId}", fontWeight = FontWeight.Bold)
            Text("Dueño: ${report.videoOwnerName} (${report.videoOwnerId})")
            Text("Razón: ${report.reason}")
            Text("Estado: ${report.status}", color = if (report.status == "pendiente") Color.Red else Color.Green)
            
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (maxWidth < 430.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = canWrite, onClick = onSuspend, modifier = Modifier.fillMaxWidth()) {
                            Text("Suspender dueño")
                        }
                        if (report.status == "pendiente") {
                            OutlinedButton(
                                onClick = { onStatusChange("resuelto") },
                                enabled = canWrite,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Resolver") }
                            OutlinedButton(
                                onClick = { onStatusChange("ignorado") },
                                enabled = canWrite,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Ignorar") }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = canWrite, onClick = onSuspend) { Text("Suspender dueño") }
                        if (report.status == "pendiente") {
                            OutlinedButton(enabled = canWrite, onClick = { onStatusChange("resuelto") }) { Text("Resolver") }
                            OutlinedButton(enabled = canWrite, onClick = { onStatusChange("ignorado") }) { Text("Ignorar") }
                        }
                    }
                }
            }
        }
    }
}

data class SuspendedProfile(
    val userId: String,
    val isSuspended: Boolean = true,
    val suspendedUntil: String? = null,
    val suspensionEndDate: String? = null,
    val suspensionReason: String? = null,
    val suspensionCount: Int = 0,
    val decisionId: String? = null,
    val ruleTitle: String? = null,
    val explanation: String? = null,
    val evidence: List<String> = emptyList(),
    val severity: String? = null,
    val appealDeadline: String? = null,
    val appealStatus: String? = null,
    val updatedAt: String? = null
)
