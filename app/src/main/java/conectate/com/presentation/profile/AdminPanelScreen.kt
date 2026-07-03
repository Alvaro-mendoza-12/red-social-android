package conectate.com.presentation.profile

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.model.Report
import conectate.com.data.model.VideoReport

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

    var selectedTabIndex by remember { mutableStateOf(0) }
    var userToSuspend by remember { mutableStateOf<String?>(null) }
    var suspendReason by remember { mutableStateOf("") }
    var suspendDays by remember { mutableStateOf("7") }

    LaunchedEffect(Unit) {
        viewModel.checkAdminAccess()
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
                        label = { Text("Razón") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = suspendDays,
                        onValueChange = { suspendDays = it },
                        label = { Text("Días (0 para permanente)") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val days = suspendDays.toIntOrNull() ?: 7
                    viewModel.suspendUser(userToSuspend!!, suspendReason, days)
                    userToSuspend = null
                    suspendReason = ""
                }) { Text("Suspender") }
            },
            dismissButton = {
                TextButton(onClick = { userToSuspend = null }) { Text("Cancelar") }
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
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("Suspendidos") })
                Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text("Usuarios") })
                Tab(selected = selectedTabIndex == 2, onClick = { selectedTabIndex = 2 }, text = { Text("Videos") })
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
                        if (suspendedProfiles.isEmpty() && !isLoading) {
                            item { Text("No hay usuarios suspendidos.") }
                        }
                        items(suspendedProfiles) { profile ->
                            SuspendedProfileCard(profile)
                        }
                    }
                    1 -> {
                        if (userReports.isEmpty() && !isLoading) {
                            item { Text("No hay reportes de usuarios.") }
                        }
                        items(userReports) { report ->
                            UserReportCard(report, onSuspend = { userToSuspend = report.reported_user_id }, onStatusChange = { status -> viewModel.updateReportStatus(report.id ?: "", status, "user") })
                        }
                    }
                    2 -> {
                        if (videoReports.isEmpty() && !isLoading) {
                            item { Text("No hay reportes de videos.") }
                        }
                        items(videoReports) { report ->
                            VideoReportCard(report, onSuspend = { userToSuspend = report.videoOwnerId }, onStatusChange = { status -> viewModel.updateReportStatus(report.id, status, "video") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuspendedProfileCard(profile: SuspendedProfile) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("ID: ${profile.userId}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            if (!profile.suspensionReason.isNullOrBlank()) {
                Text("Razón: ${profile.suspensionReason}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Hasta: ${profile.suspendedUntil ?: "Permanente"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UserReportCard(report: Report, onSuspend: () -> Unit, onStatusChange: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Reportado: ${report.reported_user_name} (${report.reported_user_id})", fontWeight = FontWeight.Bold)
            Text("Por: ${report.reporter_name}")
            Text("Razón: ${report.reason}")
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
