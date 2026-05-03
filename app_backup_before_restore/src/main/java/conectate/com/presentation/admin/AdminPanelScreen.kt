package conectate.com.presentation.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.data.model.Report
import conectate.com.data.model.VideoReport
import conectate.com.data.repository.SuspensionDurationUnit
import conectate.com.presentation.navigation.Screen



@Composable
fun AdminPanelScreen(
    navController: NavController,
    viewModel: AdminViewModel = hiltViewModel()
) {
    val adminState by viewModel.adminState.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.uiMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Panel de Administración", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    titleContentColor = MaterialTheme.colorScheme.onError,
                    navigationIconContentColor = MaterialTheme.colorScheme.onError
                )
            )
        }
    ) { padding ->
        if (!isAdmin) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Acceso restringido",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Esta seccion esta disponible solo para cuentas administradoras autorizadas.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { navController.popBackStack() }) {
                            Text("Volver")
                        }
                    }
                }
            }
            return
        }

        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("Reportes", "Reporte de Videos", "Insignias")

        Column(modifier = Modifier.padding(padding)) {
            // Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            // Tab content
            when (selectedTab) {
                0 -> ReportsTabContent(adminState, navController, viewModel)
                1 -> VideoReportsTabContent(adminState, navController, viewModel)
                2 -> BadgesTabContent(navController, viewModel)
            }
        }
    }
}


@Composable
fun ReportsTabContent(
    adminState: AdminUiState,
    navController: NavController,
    viewModel: AdminViewModel
) {
    val state = adminState
    when (state) {
        is AdminUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.error)
            }
        }

        is AdminUiState.Unauthorized -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No tienes permisos para ver esta seccion",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
        
        is AdminUiState.Success -> {
            if (state.reports.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No hay reportes pendientes",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.reports) { report ->
                        ReportCard(
                            report = report,
                            navController = navController,
                            onStatusChange = { newStatus ->
                                report.id?.let { viewModel.updateReportStatus(it, newStatus) }
                            },
                            onDelete = {
                                report.id?.let { viewModel.deleteReport(it) }
                            }
                        )
                    }
                }
            }
        }
        
        is AdminUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}


@Composable
fun VideoReportsTabContent(
    adminState: AdminUiState,
    navController: NavController,
    viewModel: AdminViewModel
) {
    val state = adminState
    when (state) {
        is AdminUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.error)
            }
        }
        is AdminUiState.Unauthorized -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No tienes permisos para revisar reportes de video",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
        is AdminUiState.Success -> {
            if (state.videoReports.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No hay reportes de videos",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.videoReports, key = { it.id }) { report ->
                        VideoReportCard(
                            report = report,
                            navController = navController,
                            onStatusChange = { viewModel.updateVideoReportStatus(report.id, it) },
                            onWarn = { viewModel.warnVideoOwner(report) },
                            onDeleteVideo = { viewModel.deleteVideoFromReport(report) },
                            onSuspendAccount = { config -> viewModel.suspendVideoOwner(report, config) },
                            onEnableAccount = { viewModel.enableVideoOwner(report) }
                        )
                    }
                }
            }
        }
        is AdminUiState.Error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = state.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}


@Composable
fun VideoReportCard(
    report: VideoReport,
    navController: NavController,
    onStatusChange: (String) -> Unit,
    onWarn: () -> Unit,
    onDeleteVideo: () -> Unit,
    onSuspendAccount: (SuspensionRequestConfig) -> Unit,
    onEnableAccount: () -> Unit
) {
    var showStatusMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSuspendDialog by remember { mutableStateOf(false) }
    var suspendDurationValueText by remember { mutableStateOf("7") }
    var suspendReasonText by remember { mutableStateOf(report.reason.ifBlank { "Incumplimiento de normas de la comunidad" }) }
    var suspendUnit by remember { mutableStateOf(SuspensionDurationUnit.DAYS) }
    var suspendUnitMenuExpanded by remember { mutableStateOf(false) }
    var suspendPermanent by remember { mutableStateOf(false) }

    val normalizedStatus = report.status.trim().lowercase()
        .replace(" ", "_")
        .replace("ó", "o")
        .replace("í", "i")
    val actionNoteLower = report.actionNote?.lowercase().orEmpty()
    val isOwnerSuspended =
        actionNoteLower.contains("cuenta suspendida") &&
            !actionNoteLower.contains("habilitada")

    var statusColor = when (normalizedStatus) {
        "pendiente" -> Color(0xFFFF9800)
        "en_revision", "en_proceso" -> Color(0xFF2196F3)
        "finalizado", "revisado" -> Color(0xFF4CAF50)
        "eliminado" -> Color(0xFFF44336)
        "advertido" -> Color(0xFF9C27B0)
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = report.videoTitle ?: "Video reportado",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(color = statusColor, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        text = when (normalizedStatus) {
                            "pendiente" -> "Pendiente"
                            "en_revision", "en_proceso" -> "En revisión"
                            "finalizado", "revisado" -> "Finalizado"
                            "eliminado" -> "Eliminado"
                            "advertido" -> "Advertido"
                            else -> report.status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            if (!report.videoThumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = report.videoThumbnailUrl,
                    contentDescription = "Miniatura",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Text("Creador: ${report.videoOwnerName ?: report.videoOwnerId}", style = MaterialTheme.typography.bodyMedium)
            Text("Reportado por: ${report.reporterName ?: report.reporterId}", style = MaterialTheme.typography.bodyMedium)
            Text("Motivo: ${report.reason}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!report.details.isNullOrBlank()) {
                Text("Detalle: ${report.details}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "Fecha: ${report.createdAt.take(16).replace("T", " ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { navController.navigate(Screen.VideoPlayer.createRoute(report.videoId)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Ver video")
                }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { showStatusMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cambiar estado")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showStatusMenu,
                        onDismissRequest = { showStatusMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Pendiente") },
                            onClick = {
                                showStatusMenu = false
                                onStatusChange("pendiente")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("En revisión") },
                            onClick = {
                                showStatusMenu = false
                                onStatusChange("en_revision")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Finalizado") },
                            onClick = {
                                showStatusMenu = false
                                onStatusChange("finalizado")
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onWarn,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Advertir")
                }
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Eliminar video")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (isOwnerSuspended) {
                            onEnableAccount()
                        } else {
                            showSuspendDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isOwnerSuspended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(if (isOwnerSuspended) "Habilitar cuenta" else "Suspender cuenta")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Eliminar video") },
            text = { Text("¿Eliminar este video de toda la plataforma? Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteVideo()
                    }
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showSuspendDialog) {
        val unitOptions = listOf(
            SuspensionDurationUnit.SECONDS to "Segundos",
            SuspensionDurationUnit.MINUTES to "Minutos",
            SuspensionDurationUnit.HOURS to "Horas",
            SuspensionDurationUnit.DAYS to "Días",
            SuspensionDurationUnit.YEARS to "Años"
        )
        val selectedUnitLabel = unitOptions.firstOrNull { it.first == suspendUnit }?.second ?: "Días"

        AlertDialog(
            onDismissRequest = { showSuspendDialog = false },
            title = { Text("Suspender cuenta") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Configura duración y motivo de la suspensión.")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Suspensión permanente",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = suspendPermanent,
                            onCheckedChange = { suspendPermanent = it }
                        )
                    }

                    if (!suspendPermanent) {
                        OutlinedTextField(
                            value = suspendDurationValueText,
                            onValueChange = { suspendDurationValueText = it.filter(Char::isDigit).take(6) },
                            singleLine = true,
                            label = { Text("Duración") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { suspendUnitMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Unidad: $selectedUnitLabel")
                            }
                            DropdownMenu(
                                expanded = suspendUnitMenuExpanded,
                                onDismissRequest = { suspendUnitMenuExpanded = false }
                            ) {
                                unitOptions.forEach { (unit, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            suspendUnit = unit
                                            suspendUnitMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = suspendReasonText,
                        onValueChange = { suspendReasonText = it.take(280) },
                        label = { Text("Motivo de suspensión") },
                        placeholder = { Text("Ej: Incumplimiento de normas de la comunidad") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        var durationValue = suspendDurationValueText.toIntOrNull()?.coerceAtLeast(1) ?: 7
                        var reason = suspendReasonText.trim().ifBlank {
                            "Incumplimiento de normas de la comunidad"
                        }
                        showSuspendDialog = false
                        onSuspendAccount(
                            SuspensionRequestConfig(
                                durationValue = durationValue,
                                durationUnit = suspendUnit,
                                reason = reason,
                                permanent = suspendPermanent
                            )
                        )
                    }
                ) {
                    Text("Suspender", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSuspendDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}


@Composable
fun ReportCard(
    report: Report,
    navController: NavController,
    onStatusChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showStatusMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    var statusColor = when (report.status) {
        "pendiente" -> Color(0xFFFF9800) // Orange
        "en_proceso" -> Color(0xFF2196F3) // Blue
        "finalizado" -> Color(0xFF4CAF50) // Green
        else -> MaterialTheme.colorScheme.outline
    }
    
    val statusText = when (report.status) {
        "pendiente" -> "Pendiente"
        "en_proceso" -> "En Proceso"
        "finalizado" -> "Finalizado"
        else -> report.status ?: "Desconocido"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Title + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reporte de: ${report.reported_user_name ?: "Usuario"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Surface(
                    color = statusColor,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
            
            Divider()
            
            // Reporter info
            Text(
                text = "Reportado por:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = report.reporter_name ?: (report.reporter_id?.take(20) ?: "ID Desconocido") + "...",
                style = MaterialTheme.typography.bodyMedium
            )
            
            // User reported
            Text(
                text = "Usuario reportado:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = report.reported_user_name ?: report.reported_user_id?.take(20) ?: "ID Desconocido",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            // Reason
            Text(
                text = "Motivo:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = report.reason ?: "Sin motivo",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            // Description
            if (!report.description.isNullOrBlank()) {
                Text(
                    text = "Descripción:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = report.description,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            // Evidence image
            if (!report.evidence_url.isNullOrBlank()) {
                Text(
                    text = "Evidencia:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                AsyncImage(
                    model = report.evidence_url,
                    contentDescription = "Evidencia",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            navController.navigate(Screen.FullScreenImage.createRoute(report.evidence_url))
                        },
                    contentScale = ContentScale.Crop
                )
            }
            
            // Date
            Text(
                text = "Fecha: ${report.created_at?.take(16)?.replace("T", " ") ?: "Desconocida"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            
            Divider()
            
            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dropdown
                Box {
                    TextButton(onClick = { showStatusMenu = true }) {
                        Text("Cambiar estado")
                        Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                    
                    DropdownMenu(
                        expanded = showStatusMenu,
                        onDismissRequest = { showStatusMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Pendiente") },
                            onClick = {
                                showStatusMenu = false
                                onStatusChange("pendiente")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("En Proceso") },
                            onClick = {
                                showStatusMenu = false
                                onStatusChange("en_proceso")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Finalizado") },
                            onClick = {
                                showStatusMenu = false
                                onStatusChange("finalizado")
                            }
                        )
                    }
                }
                
                // Delete button
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Eliminar",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Eliminar reporte") },
            text = { Text("¿Estás seguro de eliminar este reporte?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}








