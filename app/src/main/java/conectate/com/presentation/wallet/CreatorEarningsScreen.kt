package conectate.com.presentation.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.model.CreatorEarningActivity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorEarningsScreen(
    navController: NavController,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val dashboard by viewModel.creatorDashboard.collectAsStateWithLifecycle()
    val loading by viewModel.earningsLoading.collectAsStateWithLifecycle()
    val error by viewModel.earningsError.collectAsStateWithLifecycle()
    val payout = dashboard.payoutAccount
    val summary = dashboard.earnings
    val currency = payout?.currency?.takeIf { it.length == 3 } ?: "PEN"
    val payoutVerified = payout?.status?.lowercase(Locale.ROOT) in setOf("verified", "active")
    var showWithdrawalInfo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ganancias") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshEarnings, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Ganancia disponible", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    formatMoney(summary.availableCredits, currency),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { showWithdrawalInfo = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (payoutVerified) "Retiro no conectado" else "Ver requisitos de retiro")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            EarningsMetric(
                                label = "Pendiente",
                                value = formatMoney(summary.pendingCredits, currency),
                                modifier = Modifier.weight(1f)
                            )
                            EarningsMetric(
                                label = "En revisión",
                                value = formatMoney(summary.heldCredits, currency),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            EarningsMetric(
                                label = "Reservado",
                                value = formatMoney(summary.reservedCredits, currency),
                                modifier = Modifier.weight(1f)
                            )
                            EarningsMetric(
                                label = "Pagado",
                                value = formatMoney(summary.paidCredits, currency),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            dashboard.nextPayoutAt?.let { "Próxima liberación estimada: ${it.take(10)}" }
                                ?: "Próxima liberación: no programada por el servidor",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (loading && dashboard.activity.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("Actualizando ganancias…")
                    }
                }
            }

            error?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No se pudo actualizar", fontWeight = FontWeight.Black)
                            Text(message)
                            OutlinedButton(onClick = viewModel::refreshEarnings) { Text("Reintentar") }
                        }
                    }
                }
            }

            item {
                Text("Actividad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }

            if (!loading && dashboard.activity.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Sin movimientos de ganancias", fontWeight = FontWeight.Bold)
                            Text(
                                "Aquí aparecerán regalos confirmados, liberaciones de retención, ajustes y retiros.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                items(dashboard.activity, key = { it.id }) { activity ->
                    EarningActivityCard(activity = activity, currency = currency)
                }
            }

            item {
                PayoutAccountCard(
                    provider = payout?.provider,
                    status = payout?.status,
                    reference = payout?.externalAccountId,
                    currency = currency,
                    onOpenInfo = { showWithdrawalInfo = true }
                )
            }

            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Las monedas compradas por espectadores no son retirables. Las ganancias del creador son un instrumento separado y sólo se liberan tras retención, conciliación, verificación y reglas fiscales aplicables.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (showWithdrawalInfo) {
        AlertDialog(
            onDismissRequest = { showWithdrawalInfo = false },
            icon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
            title = { Text(if (payoutVerified) "Retiro pendiente de conexión" else "Verifica tu cuenta de retiro") },
            text = {
                Text(
                    if (payoutVerified) {
                        "Tu cuenta está verificada, pero esta instalación todavía no tiene conectado el proveedor de retiros. " +
                            "El botón no enviará dinero hasta disponer de un endpoint firmado, conciliación y verificación de identidad."
                    } else {
                        "La cuenta de retiro se registra y verifica en el portal seguro del proveedor. Novvin no pedirá PAN, CVV ni credenciales bancarias dentro de esta pantalla."
                    }
                )
            },
            confirmButton = { TextButton(onClick = { showWithdrawalInfo = false }) { Text("Entendido") } }
        )
    }
}

@Composable
private fun EarningsMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun EarningActivityCard(activity: CreatorEarningActivity, currency: String) {
    Card(shape = RoundedCornerShape(16.dp)) {
        ListItem(
            leadingContent = { Icon(Icons.Default.Payments, contentDescription = null) },
            headlineContent = { Text(readableEarningSource(activity.sourceType), fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(buildString {
                        append(readableEarningStatus(activity.status))
                        activity.createdAt?.take(10)?.let { append(" · $it") }
                        if (activity.netCredits.signum() <= 0 && activity.amountCoins > 0) {
                            append(" · ${formatCoins(activity.amountCoins)} monedas recibidas")
                        }
                    })
                    if (activity.grossCredits.signum() > 0) {
                        Text(
                            buildString {
                                append("Bruto ${formatMoney(activity.grossCredits, currency)}")
                                if (activity.platformFeeCredits.signum() > 0) {
                                    append(" · comisión −${formatMoney(activity.platformFeeCredits, currency)}")
                                }
                                if (activity.taxWithheldCredits.signum() > 0) {
                                    append(" · impuestos −${formatMoney(activity.taxWithheldCredits, currency)}")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            trailingContent = {
                Text(
                    if (activity.netCredits.signum() > 0) formatMoney(activity.netCredits, currency) else "Pendiente",
                    fontWeight = FontWeight.Black,
                    color = when (activity.status.lowercase(Locale.ROOT)) {
                        "refunded", "reversed" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
        )
    }
}

@Composable
private fun PayoutAccountCard(
    provider: String?,
    status: String?,
    reference: String?,
    currency: String,
    onOpenInfo: () -> Unit
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBalance, contentDescription = null)
                Spacer(Modifier.size(9.dp))
                Text("Cuenta de retiro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }
            Text(readablePayoutStatus(status.orEmpty()), fontWeight = FontWeight.Bold)
            if (provider.isNullOrBlank()) {
                Text("No hay un proveedor de retiros conectado.")
            } else {
                Text("${provider.replaceFirstChar { it.titlecase(Locale.getDefault()) }} · ${maskPayoutReference(reference) ?: "referencia protegida"}")
                Text("Moneda de liquidación: $currency", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onOpenInfo, modifier = Modifier.fillMaxWidth()) {
                Text(if (provider.isNullOrBlank()) "Cómo verificar la cuenta" else "Ver estado de la cuenta")
            }
            Text(
                "La gestión de la cuenta ocurre fuera de Novvin y nunca almacena PAN ni CVV.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Identidad (KYC) y MFA son obligatorios antes de habilitar un retiro o cambiar la cuenta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
