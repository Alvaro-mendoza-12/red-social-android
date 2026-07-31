package conectate.com.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.model.Report
import conectate.com.data.model.VideoReport
import conectate.com.data.localfallback.AdminCatalog
import conectate.com.data.localfallback.AdminBadgeOption
import conectate.com.data.localfallback.AdminModerationAppeal
import conectate.com.data.localfallback.AdminUser
import conectate.com.data.localfallback.SystemHealthResponse
import conectate.com.presentation.components.FullScreenImageDialog
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
    navController: NavController,
    viewModel: AdminPanelViewModel = hiltViewModel()
) {
    val isAdmin by viewModel.isAdmin.collectAsState()
    val adminAccess by viewModel.adminAccess.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    
    val suspendedProfiles by viewModel.suspendedProfiles.collectAsState()
    val moderationAppeals by viewModel.moderationAppeals.collectAsState()
    val userReports by viewModel.userReports.collectAsState()
    val videoReports by viewModel.videoReports.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val systemHealth by viewModel.systemHealth.collectAsState()
    val users by viewModel.users.collectAsState()
    val catalog by viewModel.catalog.collectAsState()
    val selectedUser by viewModel.selectedUser.collectAsState()
    val userSearch by viewModel.userSearch.collectAsState()
    val lastDeletedUserId by viewModel.lastDeletedUserId.collectAsState()
    val deleteCandidate by viewModel.deleteCandidate.collectAsState()

    var selectedTabIndex by remember { mutableStateOf(0) }
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

    DisposableEffect(Unit) {
        viewModel.startStatsAutoRefresh()
        onDispose { viewModel.stopStatsAutoRefresh() }
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
                            modifier = Modifier.fillMaxWidth().menuAnchor()
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
                            modifier = Modifier.fillMaxWidth().menuAnchor()
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
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
            ScrollableTabRow(selectedTabIndex = selectedTabIndex) {
                Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("Resumen") })
                Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text("Usuarios") })
                Tab(selected = selectedTabIndex == 2, onClick = { selectedTabIndex = 2 }, text = { Text("Suspendidos") })
                Tab(selected = selectedTabIndex == 3, onClick = { selectedTabIndex = 3 }, text = { Text("Reportes") })
                Tab(selected = selectedTabIndex == 4, onClick = { selectedTabIndex = 4 }, text = { Text("Videos") })
                Tab(selected = selectedTabIndex == 5, onClick = { selectedTabIndex = 5 }, text = { Text("Apelaciones") })
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

            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (selectedTabIndex) {
                    0 -> {
                        item(key = "admin-summary") { AdminSummary(stats, systemHealth) }
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
                                onOpen = { viewModel.loadUserDetails(summary.id) },
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
                }
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
                if (index < badges.lastIndex) Divider()
            }
        }
    }
}

@Composable
private fun AdminSummary(
    stats: conectate.com.data.localfallback.AdminDashboardStats,
    systemHealth: SystemHealthResponse?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SystemHealthPanel(systemHealth)
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
private fun SystemHealthPanel(health: SystemHealthResponse?) {
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
                Text(
                    "No se pudo comprobar el estado de los servicios. Pulsa actualizar para intentarlo otra vez.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
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
                    if (user.avatarUrl.isNullOrBlank()) {
                        Text(
                            text = user.displayName.trim().firstOrNull()?.uppercase() ?: "?",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    } else {
                        AsyncImage(
                            model = user.avatarUrl,
                            contentDescription = "Foto de ${user.displayName}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(user.displayName, fontWeight = FontWeight.Bold)
                    Text(user.email, style = MaterialTheme.typography.bodySmall)
                    if (user.username.isNotBlank()) Text("@${user.username}", style = MaterialTheme.typography.bodySmall)
                }
                if (user.isTestAccount) AssistChip(onClick = {}, label = { Text("PRUEBA") })
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Ver detalles")
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
            Divider()
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
