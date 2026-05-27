package conectate.com.presentation.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorProgramScreen(
    navController: NavController,
    viewModel: CreatorProgramViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dashboard = state.dashboard
    val application = dashboard.application
    val payout = dashboard.payoutAccount
    val snackbarHostState = remember { SnackbarHostState() }

    var niche by remember(application?.id) { mutableStateOf(application?.niche.orEmpty()) }
    var portfolioUrl by remember(application?.id) { mutableStateOf(application?.portfolioUrl.orEmpty()) }
    var payoutCountry by remember(application?.id) { mutableStateOf(application?.payoutCountry.orEmpty()) }
    var audienceSize by remember(application?.id) { mutableStateOf(application?.audienceSize?.toString().orEmpty()) }

    var payoutProvider by remember(payout?.userId) { mutableStateOf(payout?.provider ?: "manual") }
    var payoutExternalId by remember(payout?.userId) { mutableStateOf(payout?.externalAccountId.orEmpty()) }
    var payoutAccountCountry by remember(payout?.userId) { mutableStateOf(payout?.country.orEmpty()) }
    var payoutCurrency by remember(payout?.userId) { mutableStateOf(payout?.currency ?: "USD") }

    LaunchedEffect(state.message, state.error) {
        val message = state.message ?: state.error
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearTransientMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Programa de creadores") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CreatorStatusCard(
                status = application?.status ?: "sin solicitud",
                rejectionReason = application?.rejectionReason
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Solicitud", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Postúlate para monetizar con gifts, premium y reparto de anuncios. Puedes actualizar tus datos sin duplicar solicitudes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = niche,
                        onValueChange = { niche = it },
                        label = { Text("Nicho principal") },
                        placeholder = { Text("Gaming, música, anime, deporte...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = portfolioUrl,
                        onValueChange = { portfolioUrl = it },
                        label = { Text("Portfolio o red social") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = payoutCountry,
                            onValueChange = { payoutCountry = it.uppercase().take(2) },
                            label = { Text("País") },
                            modifier = Modifier.weight(0.45f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = audienceSize,
                            onValueChange = { audienceSize = it.filter(Char::isDigit).take(9) },
                            label = { Text("Audiencia") },
                            modifier = Modifier.weight(0.55f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.submitApplication(
                                niche = niche,
                                portfolioUrl = portfolioUrl,
                                payoutCountry = payoutCountry,
                                audienceSize = audienceSize.toIntOrNull() ?: 0
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = niche.isNotBlank() && !state.isLoading
                    ) {
                        Text(if (application == null) "Enviar solicitud" else "Actualizar solicitud")
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Pagos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Configura un destino de pago. Queda en revisión hasta que administración lo verifique.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = payoutProvider,
                        onValueChange = { payoutProvider = it.lowercase().take(16) },
                        label = { Text("Proveedor: stripe, paypal, bank o manual") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = payoutExternalId,
                        onValueChange = { payoutExternalId = it },
                        label = { Text("Cuenta externa o referencia") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = payoutAccountCountry,
                            onValueChange = { payoutAccountCountry = it.uppercase().take(2) },
                            label = { Text("País") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = payoutCurrency,
                            onValueChange = { payoutCurrency = it.uppercase().take(3) },
                            label = { Text("Moneda") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.savePayout(
                                provider = payoutProvider,
                                externalAccountId = payoutExternalId,
                                country = payoutAccountCountry,
                                currency = payoutCurrency
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isLoading
                    ) {
                        Text("Guardar método de pago")
                    }
                    if (payout != null) {
                        Text(
                            "Estado del pago: ${creatorStatusLabel(payout.status)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = creatorStatusColor(payout.status)
                        )
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ganancias", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    EarningRow("Pendiente", dashboard.earnings.pendingCredits)
                    EarningRow("Disponible", dashboard.earnings.availableCredits)
                    EarningRow("Pagado", dashboard.earnings.paidCredits)
                    Divider()
                    EarningRow("Total", dashboard.earnings.totalCredits, strong = true)
                }
            }

            if (state.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun CreatorStatusCard(status: String, rejectionReason: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = creatorStatusColor(status).copy(alpha = 0.12f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Estado", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                creatorStatusLabel(status),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = creatorStatusColor(status)
            )
            if (!rejectionReason.isNullOrBlank()) {
                Text(rejectionReason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EarningRow(label: String, credits: Double, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${formatCredits(credits)} créditos",
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal
        )
    }
}

private fun creatorStatusLabel(status: String): String {
    return when (status.lowercase()) {
        "pending" -> "Pendiente de revisión"
        "approved" -> "Aprobado"
        "rejected" -> "Rechazado"
        "paused" -> "Pausado"
        "verified" -> "Verificado"
        "disabled" -> "Deshabilitado"
        else -> "Sin solicitud"
    }
}

private fun creatorStatusColor(status: String): Color {
    return when (status.lowercase()) {
        "approved", "verified" -> Color(0xFF1B8F4D)
        "rejected", "disabled" -> Color(0xFFB3261E)
        "paused" -> Color(0xFF7C5800)
        "pending" -> Color(0xFF6750A4)
        else -> Color(0xFF5F6368)
    }
}

private fun formatCredits(value: Double): String {
    return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
}
