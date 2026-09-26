package conectate.com.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
fun MyReportsScreen(
    navController: NavController,
    viewModel: MyReportsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis Reportes") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (val state = uiState) {
                is MyReportsUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is MyReportsUiState.Success -> {
                    if (state.reports.isEmpty() && state.videoReports.isEmpty()) {
                        EmptyReportsView(onAccept = { navController.popBackStack() })
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (state.reports.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Reportes a usuarios",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                items(state.reports) { report ->
                                    MyReportItem(report)
                                }
                            }

                            if (state.videoReports.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Reportes a videos",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                items(state.videoReports) { report ->
                                    MyVideoReportItem(report)
                                }
                            }
                        }
                    }
                }
                is MyReportsUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { navController.popBackStack() }) {
                                Text("Aceptar")
                            }
                         }
                    }
                }
            }
        }
    }
}

@Composable
fun MyReportItem(report: Report) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val reportedName = report.reported_user_name ?: "Usuario"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reporte a: $reportedName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                StatusBadge(report.status ?: "pendiente")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Motivo: ${report.reason ?: "Sin motivo"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            if (!report.description.isNullOrBlank()) {
                Text(
                    text = "Descripción: ${report.description}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            val dateText = report.created_at?.let {
                if (it.length >= 16) it.take(16).replace("T", " ") else it
            } ?: "Fecha desconocida"
            Text(
                text = "Fecha: $dateText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun MyVideoReportItem(report: VideoReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val videoTitle = report.videoTitle ?: "Video"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Video: $videoTitle",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                StatusBadge(report.status)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Motivo: ${report.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            if (!report.details.isNullOrBlank()) {
                Text(
                    text = "Descripción: ${report.details}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            val dateText = if (report.createdAt.length >= 16) {
                report.createdAt.take(16).replace("T", " ")
            } else {
                report.createdAt
            }
            Text(
                text = "Fecha: $dateText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val normalized = status.trim().lowercase()
        .replace(" ", "_")
        .replace("ó", "o")
        .replace("í", "i")
    val (color, text) = when (normalized) {
        "pendiente" -> MaterialTheme.colorScheme.error to "Pendiente"
        "en_proceso", "en_revision" -> MaterialTheme.colorScheme.primary to "En revisión"
        "finalizado" -> androidx.compose.ui.graphics.Color(0xFF4CAF50) to "Finalizado"
        "revisado" -> MaterialTheme.colorScheme.primary to "Revisado"
        "eliminado" -> MaterialTheme.colorScheme.error to "Eliminado"
        "advertido" -> Color(0xFF9C27B0) to "Advertido"
        else -> MaterialTheme.colorScheme.outline to "Desconocido"
    }
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun EmptyReportsView(onAccept: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No tienes ningún reporte",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onAccept,
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("Aceptar")
        }
    }
}
