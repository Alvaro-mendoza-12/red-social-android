package conectate.com.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.model.Report
import conectate.com.data.model.VideoReport
import conectate.com.data.localfallback.AdminCatalog
import conectate.com.data.localfallback.AdminUser
import conectate.com.presentation.components.FullScreenImageDialog
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
    navController: NavController,
    viewModel: AdminPanelViewModel = hiltViewModel()
) {
    val isAdmin by viewModel.isAdmin.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    
    val suspendedProfiles by viewModel.suspendedProfiles.collectAsState()
    val userReports by viewModel.userReports.collectAsState()
    val videoReports by viewModel.videoReports.collectAsState()
    val stats by viewModel.stats.collectAsState()
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
    // Estado para eliminar usuario completamente
    var userToDelete by remember { mutableStateOf<AdminUser?>(null) }

    LaunchedEffect(Unit) {
        viewModel.checkAdminAccess()
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

    if (userToSuspend != null) {
        AlertDialog(
            onDismissRequest = { userToSuspend = null },
            title = { Text("Suspender Usuario") },
            text = {
                Column {
                    Text("ID: ${userToSuspend}")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = suspendReason,
                        onValueChange = { suspendReason = it },
                        label = { Text("Motivo obligatorio") },
                        supportingText = { Text("Se guardará en el registro de auditoría") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                        viewModel.suspendUser(userToSuspend!!, suspendReason.trim(), days ?: return@Button)
                        userToSuspend = null
                        suspendReason = ""
                        suspendDays = "7"
                    }
                ) { Text("Suspender") }
            },
            dismissButton = {
                TextButton(onClick = { userToSuspend = null }) { Text("Cancelar") }
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
                    enabled = !isLoading && !target.isAdmin && (target.isTestAccount || target.isSuspended),
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
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = selectedTabIndex) {
                Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("Resumen") })
                Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text("Usuarios") })
                Tab(selected = selectedTabIndex == 2, onClick = { selectedTabIndex = 2 }, text = { Text("Suspendidos") })
                Tab(selected = selectedTabIndex == 3, onClick = { selectedTabIndex = 3 }, text = { Text("Reportes") })
                Tab(selected = selectedTabIndex == 4, onClick = { selectedTabIndex = 4 }, text = { Text("Videos") })
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
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (selectedTabIndex) {
                    0 -> {
                        item { AdminSummary(stats) }
                    }
                    1 -> {
                        item {
                            AdminUserSearch(
                                query = userSearch,
                                onQueryChange = viewModel::setUserSearch,
                                onSearch = viewModel::searchUsers
                            )
                        }
                        if (users.isEmpty() && !isLoading) item { Text("No se encontraron usuarios.") }
                        items(users, key = { it.id }) { summary ->
                            val user = selectedUser?.takeIf { it.id == summary.id } ?: summary
                            AdminUserCard(
                                user = user,
                                catalog = catalog,
                                expanded = selectedUser?.id == summary.id,
                                onOpen = { viewModel.loadUserDetails(summary.id) },
                                onVerifiedChange = { viewModel.updateUserVerification(summary.id, it) },
                                onBadgeChange = { badgeId, enabled ->
                                    viewModel.updateUserBadge(summary.id, badgeId, enabled)
                                },
                                onFrameChange = { viewModel.updateUserFrame(summary.id, it) },
                                onSuspend = { userToSuspend = summary.id },
                                onReactivate = { viewModel.unsuspendUser(summary.id) },
                                onDelete = { viewModel.prepareUserDeletion(user.id) }
                            )
                        }
                    }
                    2 -> {
                        if (suspendedProfiles.isEmpty() && !isLoading) {
                            item { Text("No hay usuarios suspendidos.") }
                        }
                        items(suspendedProfiles) { profile ->
                            SuspendedProfileCard(
                                profile = profile,
                                onReactivate = { viewModel.unsuspendUser(profile.userId) },
                                onDelete = { viewModel.prepareUserDeletion(profile.userId) }
                            )
                        }
                    }
                    3 -> {
                        if (userReports.isEmpty() && !isLoading) {
                            item { Text("No hay reportes.") }
                        }
                        items(userReports) { report ->
                            UserReportCard(
                                report = report,
                                onSuspend = { userToSuspend = report.reported_user_id },
                                onStatusChange = { status -> viewModel.updateReportStatus(report.id ?: "", status, "user") }
                            )
                        }
                    }
                    4 -> {
                        if (videoReports.isEmpty() && !isLoading) {
                            item { Text("No hay reportes de videos.") }
                        }
                        items(videoReports) { report ->
                            VideoReportCard(
                                report = report,
                                onSuspend = { userToSuspend = report.videoOwnerId },
                                onStatusChange = { status -> viewModel.updateReportStatus(report.id, status, "video") }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminSummary(stats: conectate.com.data.localfallback.AdminDashboardStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Estado de moderación", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        AdminMetric("Usuarios", stats.totalUsers, Icons.Default.People)
        AdminMetric("Videos publicados", stats.totalVideos, Icons.Default.VideoLibrary)
        AdminMetric("Cuentas suspendidas", stats.suspendedUsers, Icons.Default.Block)
        AdminMetric("Reportes de usuarios pendientes", stats.pendingUserReports, Icons.Default.Report)
        AdminMetric("Reportes de videos pendientes", stats.pendingVideoReports, Icons.Default.Flag)
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
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun AdminUserCard(
    user: AdminUser,
    catalog: AdminCatalog,
    expanded: Boolean,
    onOpen: () -> Unit,
    onVerifiedChange: (Boolean) -> Unit,
    onBadgeChange: (String, Boolean) -> Unit,
    onFrameChange: (String) -> Unit,
    onSuspend: () -> Unit,
    onReactivate: () -> Unit,
    onDelete: () -> Unit
) {
    var frameMenuExpanded by remember(user.id) { mutableStateOf(false) }
    val badgeIds = user.badges.map { it.id }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = user.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)),
                    contentScale = ContentScale.Crop
                )
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
                        onCheckedChange = onVerifiedChange
                    )
                }

                Text("Insignias", fontWeight = FontWeight.SemiBold)
                catalog.badges.forEach { badge ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
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
                    OutlinedButton(onClick = { frameMenuExpanded = true }) {
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

                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!user.isAdmin) {
                        if (user.isSuspended) {
                            OutlinedButton(onClick = onReactivate, modifier = Modifier.fillMaxWidth()) { Text("Reactivar") }
                        } else {
                            OutlinedButton(onClick = onSuspend, modifier = Modifier.fillMaxWidth()) { Text("Suspender") }
                        }
                    }
                    if (!user.isAdmin && (user.isTestAccount || user.isSuspended)) {
                        Button(
                            onClick = onDelete,
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
private fun SuspendedProfileCard(
    profile: SuspendedProfile,
    onReactivate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("ID: ${profile.userId}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            if (!profile.suspensionReason.isNullOrBlank()) {
                Text("Razón: ${profile.suspensionReason}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Hasta: ${profile.suspendedUntil ?: "Permanente"}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReactivate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reactivar")
                }
                Button(
                    onClick = onDelete,
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
private fun UserReportCard(report: Report, onSuspend: () -> Unit, onStatusChange: (String) -> Unit) {
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
            
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSuspend) { Text("Suspender") }
                if (report.status == "pendiente") {
                    OutlinedButton(onClick = { onStatusChange("resuelto") }) { Text("Resolver") }
                    OutlinedButton(onClick = { onStatusChange("ignorado") }) { Text("Ignorar") }
                }
            }
        }
    }
}

@Composable
private fun VideoReportCard(report: VideoReport, onSuspend: () -> Unit, onStatusChange: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Video ID: ${report.videoId}", fontWeight = FontWeight.Bold)
            Text("Dueño: ${report.videoOwnerName} (${report.videoOwnerId})")
            Text("Razón: ${report.reason}")
            Text("Estado: ${report.status}", color = if (report.status == "pendiente") Color.Red else Color.Green)
            
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSuspend) { Text("Suspender Dueño") }
                if (report.status == "pendiente") {
                    OutlinedButton(onClick = { onStatusChange("resuelto") }) { Text("Resolver") }
                    OutlinedButton(onClick = { onStatusChange("ignorado") }) { Text("Ignorar") }
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
    val updatedAt: String? = null
)
