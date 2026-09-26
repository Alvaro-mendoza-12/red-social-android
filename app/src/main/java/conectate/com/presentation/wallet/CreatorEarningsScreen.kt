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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.model.CreatorEarningActivity
import java.math.BigDecimal
import java.math.RoundingMode
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
    val payoutSummary by viewModel.payoutSummary.collectAsStateWithLifecycle()
    val payoutLoading by viewModel.payoutLoading.collectAsStateWithLifecycle()
    val payoutMessage by viewModel.payoutMessage.collectAsStateWithLifecycle()
    val payout = dashboard.payoutAccount
    val summary = dashboard.earnings
    val currency = payoutSummary?.currency?.takeIf { it.length == 3 }
        ?: payout?.currency?.takeIf { it.length == 3 }
        ?: "USD"
    val payoutVerified = payoutSummary?.account?.status.equals("VERIFIED", ignoreCase = true)
    var showWithdrawalInfo by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showRequestDialog by remember { mutableStateOf(false) }
    var payoutEmail by remember { mutableStateOf("") }
    var payoutAmount by remember { mutableStateOf("") }

    val serverBalances = payoutSummary?.balances
    val availableLabel = serverBalances?.let { formatMinorMoney(it.availableMinor, currency) }
        ?: formatMoney(summary.availableCredits, currency)
    val pendingLabel = serverBalances?.let { formatMinorMoney(it.pendingMinor, currency) }
        ?: formatMoney(summary.pendingCredits, currency)
    val heldLabel = serverBalances?.let { formatMinorMoney(it.heldMinor, currency) }
        ?: formatMoney(summary.heldCredits, currency)
    val paidLabel = serverBalances?.let { formatMinorMoney(it.paidMinor, currency) }
        ?: formatMoney(summary.paidCredits, currency)
    val totalCoins = (serverBalances?.totalCoins?.takeIf { it > 0 }
        ?: summary.totalCoins.toLong().takeIf { it > 0 }
        ?: 0L)
    val pendingCoins = serverBalances?.pendingCoins?.takeIf { it > 0 }
        ?: summary.pendingCoins.toLong()
    val heldCoins = serverBalances?.heldCoins?.takeIf { it > 0 }
        ?: summary.heldCoins.toLong()
    val paidCoins = serverBalances?.paidCoins?.takeIf { it > 0 }
        ?: summary.paidCoins.toLong()
    val totalMinor = serverBalances?.let {
        (it.pendingMinor + it.availableMinor + it.heldMinor + it.paidMinor - it.recoveryDebtMinor)
            .coerceAtLeast(0L)
    } ?: 0L
    val hasServerMoneyValuation = payoutSummary?.configured == true || totalMinor > 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ganancias") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.refreshEarnings()
                            viewModel.refreshPayouts()
                        },
                        enabled = !loading && !payoutLoading,
                    ) {
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
                                Text(
                                    "Ganancia acumulada",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    if (hasServerMoneyValuation) {
                                        formatMinorMoney(totalMinor, currency)
                                    } else {
                                        "Conversión monetaria pendiente"
                                    },
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black
                                )
                                if (totalCoins > 0) {
                                    Text(
                                        "Equivalencia respaldada por ${formatCoins(totalCoins.toInt())} monedas recibidas · retiro en preparación",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { showWithdrawalInfo = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false,
                        ) {
                            Text("Retiros próximamente")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            EarningsMetric(
                                label = "Pendiente",
                                value = formatCoinOrMoney(pendingCoins, pendingLabel),
                                modifier = Modifier.weight(1f)
                            )
                            EarningsMetric(
                                label = "En revisión",
                                value = formatCoinOrMoney(heldCoins, heldLabel),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            EarningsMetric(
                                label = "Reservado",
                                value = serverBalances?.recoveryDebtMinor?.let { formatMinorMoney(it, currency) }
                                    ?: formatMoney(summary.reservedCredits, currency),
                                modifier = Modifier.weight(1f)
                            )
                            EarningsMetric(
                                label = "Pagado",
                                value = formatCoinOrMoney(paidCoins, paidLabel),
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
                    provider = payoutSummary?.account?.provider ?: payout?.provider,
                    status = payoutSummary?.account?.status ?: payout?.status,
                    reference = payoutSummary?.account?.emailMasked ?: payout?.externalAccountId,
                    currency = currency,
                    onOpenInfo = {
                        if (payoutSummary?.configured == true && payoutSummary?.account == null) {
                            showAccountDialog = true
                        } else {
                            showWithdrawalInfo = true
                        }
                    }
                )
            }

            payoutSummary?.requests?.take(10)?.let { requests ->
                if (requests.isNotEmpty()) {
                    item {
                        Text("Solicitudes de retiro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    }
                    items(requests, key = { it.id }) { request ->
                        Card(shape = RoundedCornerShape(16.dp)) {
                            ListItem(
                                headlineContent = {
                                    Text(formatMinorMoney(request.amountMinor, request.currency), fontWeight = FontWeight.Bold)
                                },
                                supportingContent = {
                                    Text("${readablePayoutStatus(request.status)}${request.createdAt?.take(10)?.let { " · $it" }.orEmpty()}")
                                },
                                trailingContent = {
                                    if (request.status.equals("PENDING", ignoreCase = true)) {
                                        TextButton(
                                            onClick = { viewModel.cancelCreatorPayout(request.id) },
                                            enabled = !payoutLoading,
                                        ) { Text("Cancelar") }
                                    }
                                },
                            )
                        }
                    }
                }
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
            title = { Text("Estado de retiros") },
            text = {
                Text(
                    when {
                        payoutSummary?.configured != true ->
                            "PayPal Sandbox aún no tiene todas las credenciales, el webhook firmado y la tasa comercial configurados en el servidor. No se enviará dinero con una configuración incompleta."
                        payoutSummary?.creatorVerified != true ->
                            "Solo los creadores verificados pueden retirar ganancias originadas en regalos comprados con dinero real."
                        payoutSummary?.account == null ->
                            "Registra el correo verificado de tu cuenta PayPal. Novvin nunca pedirá tu contraseña, PAN ni CVV."
                        !payoutVerified ->
                            "La cuenta ${payoutSummary?.account?.emailMasked.orEmpty()} espera verificación manual del administrador."
                        payoutSummary?.enabled != true ->
                            "La arquitectura Sandbox está preparada, pero los retiros permanecen desactivados hasta completar las pruebas externas de PayPal."
                        else ->
                            "Tu cuenta está lista. Cada solicitud requiere aprobación manual y se concilia con PayPal antes de marcarse como pagada."
                    }
                )
            },
            confirmButton = { TextButton(onClick = { showWithdrawalInfo = false }) { Text("Entendido") } }
        )
    }

    if (showAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text("Cuenta PayPal del creador") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Usa el correo verificado de PayPal. La app lo envía cifrado al backend y solo muestra una referencia enmascarada.")
                    OutlinedTextField(
                        value = payoutEmail,
                        onValueChange = { payoutEmail = it.take(254) },
                        label = { Text("Correo PayPal") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Moneda de liquidación: $currency", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.configurePayoutAccount(payoutEmail, currency)
                        showAccountDialog = false
                    },
                    enabled = payoutEmail.contains('@') && !payoutLoading,
                ) { Text("Registrar") }
            },
            dismissButton = { TextButton(onClick = { showAccountDialog = false }) { Text("Cancelar") } },
        )
    }

    if (showRequestDialog) {
        val requestedMinor = payoutAmount.toBigDecimalOrNull()
            ?.movePointRight(2)
            ?.setScale(0, RoundingMode.DOWN)
            ?.longValueExactOrNull()
            ?: 0L
        AlertDialog(
            onDismissRequest = { showRequestDialog = false },
            title = { Text("Solicitar retiro") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Disponible: $availableLabel")
                    Text("Mínimo: ${formatMinorMoney(payoutSummary?.minimumMinor ?: 0, currency)}")
                    OutlinedTextField(
                        value = payoutAmount,
                        onValueChange = { payoutAmount = it.filter { character -> character.isDigit() || character == '.' }.take(16) },
                        label = { Text("Monto en $currency") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("La solicitud quedará pendiente de aprobación manual; Android nunca ordena el pago directamente.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.requestCreatorPayout(requestedMinor)
                        showRequestDialog = false
                    },
                    enabled = requestedMinor >= (payoutSummary?.minimumMinor ?: Long.MAX_VALUE) &&
                        requestedMinor <= (payoutSummary?.balances?.availableMinor ?: 0) &&
                        !payoutLoading,
                ) { Text("Solicitar") }
            },
            dismissButton = { TextButton(onClick = { showRequestDialog = false }) { Text("Cancelar") } },
        )
    }

    payoutMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearPayoutMessage,
            title = { Text("Retiros") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearPayoutMessage) { Text("Entendido") } },
        )
    }
}

private fun BigDecimal.longValueExactOrNull(): Long? = runCatching { longValueExact() }.getOrNull()

private fun formatMinorMoney(amountMinor: Long, currency: String): String =
    formatMoney(BigDecimal.valueOf(amountMinor, 2), currency)

private fun formatCoinOrMoney(coins: Long, fallback: String): String =
    if (coins > 0) "${formatCoins(coins.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())} monedas" else fallback

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
                Text(
                    "${provider.replaceFirstChar {
                        it.titlecase(androidx.compose.ui.platform.LocalLocale.current.platformLocale)
                    }} · ${maskPayoutReference(reference) ?: "referencia protegida"}"
                )
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
