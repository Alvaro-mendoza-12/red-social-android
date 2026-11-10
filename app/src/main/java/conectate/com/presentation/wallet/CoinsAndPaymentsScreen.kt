package conectate.com.presentation.wallet

import conectate.com.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.model.WalletTransaction
import conectate.com.presentation.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinsAndPaymentsScreen(
    navController: NavController,
    mainNavController: NavController = navController,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val balance by viewModel.coinBalance.collectAsStateWithLifecycle()
    val debt by viewModel.coinDebt.collectAsStateWithLifecycle()
    val capabilities by viewModel.billingCapabilities.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val transactionsHasMore by viewModel.transactionsHasMore.collectAsStateWithLifecycle()
    val historyLoadingMore by viewModel.historyLoadingMore.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val lastUpdatedAt by viewModel.walletLastUpdatedAt.collectAsStateWithLifecycle()
    val verifying by viewModel.billingVerifying.collectAsStateWithLifecycle()
    val billingMessage by viewModel.billingMessage.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityForBilling() }
    val accountId = viewModel.currentAccountId()
    var showRecharge by remember { mutableStateOf(false) }
    var showLimits by remember { mutableStateOf(false) }
    var selectedReceipt by remember { mutableStateOf<WalletTransaction?>(null) }
    var playProducts by remember(capabilities.products) { mutableStateOf<List<PlayCoinProduct>>(emptyList()) }
    var catalogIssues by remember(capabilities.products) { mutableStateOf<List<PlayCatalogIssue>>(emptyList()) }
    var catalogLoading by remember(capabilities.enabled) { mutableStateOf(capabilities.enabled) }
    val spending = remember(transactions, transactionsHasMore) {
        calculateWalletSpend(transactions, hasMore = transactionsHasMore)
    }

    val billingController = remember(capabilities.enabled, capabilities.products, accountId) {
        if (!capabilities.enabled || accountId.isBlank()) null else GooglePlayBillingController(
            context = context,
            accountId = accountId,
            productIds = capabilities.products.map { it.productId }.toSet(),
            onProducts = {
                playProducts = it
                catalogLoading = false
            },
            onCatalogIssues = { catalogIssues = it },
            onPurchased = viewModel::verifyGooglePlayPurchase,
            onPending = {
                viewModel.showBillingMessage(
                    "Pago pendiente. Las monedas se acreditarán cuando Google Play confirme el cobro."
                )
            },
            onCanceled = {
                viewModel.showBillingMessage("Compra cancelada. No se realizó ningún cobro.")
            },
            onError = {
                catalogLoading = false
                viewModel.showBillingMessage(it)
            }
        )
    }

    DisposableEffect(billingController) {
        billingController?.start()
        onDispose { billingController?.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monedas y pagos") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
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
                WalletBalanceCard(
                    balance = balance,
                    debt = debt,
                    lastUpdatedAt = lastUpdatedAt,
                    rechargeEnabled = capabilities.enabled && playProducts.isNotEmpty() && !verifying,
                    onRecharge = { showRecharge = true }
                )
            }

            item {
                SectionTitle("Paquetes", "El precio final y la moneda los entrega Google Play")
            }

            when {
                loading && lastUpdatedAt == null -> item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("Actualizando disponibilidad…")
                    }
                }
                !capabilities.enabled -> item {
                    UnavailablePurchasesCard()
                }
                catalogLoading -> item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("Consultando precios localizados…")
                    }
                }
                playProducts.isEmpty() -> item {
                    UnavailablePurchasesCard(
                        title = "Catálogo no disponible",
                        body = "Google Play no devolvió productos publicados para esta cuenta o país. No se iniciará ningún cobro."
                    )
                }
                else -> items(
                    capabilities.products.sortedBy { it.coins },
                    key = { "package-${it.productId}" }
                ) { coinProduct ->
                    val playProduct = playProducts.firstOrNull { it.productId == coinProduct.productId }
                    val issue = catalogIssues.firstOrNull { it.productId == coinProduct.productId }
                    CoinPackageCard(
                        coins = coinProduct.coins,
                        price = playProduct?.formattedPrice,
                        availability = issue?.userFacingDescription(),
                        enabled = playProduct != null && !verifying,
                        onClick = {
                            val host = activity
                            if (host == null) {
                                viewModel.showBillingMessage("No se pudo abrir Google Play en esta pantalla.")
                            } else {
                                billingController?.launch(host, coinProduct.productId)
                            }
                        }
                    )
                }
            }

            item {
                SpendingProtectionCard(
                    spentToday = spending.spentTodayCoins,
                    spentWeek = spending.spentWeekCoins,
                    historyComplete = spending.historyComplete,
                    dailyLimit = capabilities.giftLimits.maxCoinsPerDay,
                    weeklyLimit = capabilities.giftLimits.maxCoinsPerWeek,
                    onOpen = { showLimits = true }
                )
            }

            item {
                Card(
                    onClick = { navController.navigate(Screen.CreatorEarnings.route) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Savings, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Ganancias del creador", fontWeight = FontWeight.Black)
                            Text(
                                stringResource(R.string.audit_earnings_disclosure),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            item {
                SectionTitle("Historial y recibos", "Compras, regalos, ajustes y reembolsos")
            }

            if (transactions.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            if (loading) "Actualizando movimientos…" else "Todavía no hay movimientos para mostrar.",
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(transactions, key = { it.id }) { transaction ->
                    Card(onClick = { selectedReceipt = transaction }, shape = RoundedCornerShape(16.dp)) {
                        ListItem(
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) },
                            headlineContent = {
                                Text(
                                    transaction.description ?: transaction.type.toReadableTransactionType(),
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            supportingContent = {
                                Text(buildString {
                                    append("Saldo posterior: ${formatCoins(transaction.balanceAfterDiamonds)}")
                                    transaction.createdAt?.take(16)?.replace('T', ' ')?.let { append(" · $it") }
                                    append(" · ${transaction.status.toReadableTransactionStatus()}")
                                })
                            },
                            trailingContent = {
                                Text(
                                    text = if (transaction.amountDiamonds > 0) {
                                        "+${formatCoins(transaction.amountDiamonds)}"
                                    } else {
                                        "−${formatCoins(-transaction.amountDiamonds)}"
                                    },
                                    color = if (transaction.amountDiamonds >= 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    fontWeight = FontWeight.Black
                                )
                            }
                        )
                    }
                }
            }

            if (transactionsHasMore) {
                item {
                    OutlinedButton(
                        onClick = viewModel::loadMoreTransactions,
                        enabled = !historyLoadingMore,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (historyLoadingMore) {
                            CircularProgressIndicator(Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                        }
                        Text("Cargar más movimientos")
                    }
                }
            }

            if (transactions.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { exportWalletHistory(context, transactions) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Exportar historial")
                    }
                }
            }

            item {
                HorizontalDivider()
            }

            item {
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://play.google.com/store/account/paymentmethods") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Administrar métodos en Google Play")
                }
            }

            item {
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://support.google.com/googleplay/answer/2479637") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Ayuda con una compra o reembolso")
                }
            }

            item {
                OutlinedButton(
                    onClick = { mainNavController.navigate(Screen.Novvin.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Contactar soporte Novvin")
                }
            }

            item {
                Text(
                    "Adjunta sólo la referencia del proveedor al pedir ayuda. Novvin no solicita ni almacena el número completo de tarjeta, PAN ni CVV; los métodos y el cobro son gestionados por Google Play.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRecharge) {
        GooglePlayRechargeDialog(
            accountId = accountId,
            onDismiss = { showRecharge = false },
            viewModel = viewModel
        )
    }

    if (showLimits) {
        AlertDialog(
            onDismissRequest = { showLimits = false },
            icon = { Icon(Icons.Default.Shield, contentDescription = null) },
            title = { Text("Control de gasto") },
            text = {
                val weeklyCopy = if (capabilities.giftLimits.maxCoinsPerWeek > 0) {
                    "El límite semanal vigente es ${formatCoins(capabilities.giftLimits.maxCoinsPerWeek)} monedas. "
                } else {
                    "El servidor todavía no publicó un límite semanal personal. "
                }
                Text(
                    "Tu límite diario vigente es ${formatCoins(capabilities.giftLimits.maxCoinsPerDay)} monedas. " +
                        weeklyCopy +
                        "El servidor valida el límite, la frecuencia y el saldo dentro de la misma transacción. " +
                        "La configuración personal de aumentos sólo se habilitará con el servicio de riesgo; nunca se simula en el dispositivo."
                )
            },
            confirmButton = { TextButton(onClick = { showLimits = false }) { Text("Entendido") } }
        )
    }

    selectedReceipt?.let { transaction ->
        AlertDialog(
            onDismissRequest = { selectedReceipt = null },
            icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) },
            title = { Text("Detalle del movimiento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(transaction.description ?: transaction.type.toReadableTransactionType(), fontWeight = FontWeight.Bold)
                    Text("Referencia: ${transaction.id}")
                    Text("Importe: ${transaction.amountDiamonds} monedas")
                    Text("Saldo posterior: ${transaction.balanceAfterDiamonds} monedas")
                    Text("Estado: ${transaction.status.toReadableTransactionStatus()}")
                    transaction.providerReference?.let { Text("Referencia del proveedor: ${maskPayoutReference(it)}") }
                    transaction.createdAt?.let { Text("Fecha: ${it.replace('T', ' ').take(19)}") }
                }
            },
            confirmButton = { TextButton(onClick = { selectedReceipt = null }) { Text("Cerrar") } }
        )
    }

    billingMessage?.takeUnless { showRecharge }?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearBillingMessage,
            title = { Text("Google Play") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearBillingMessage) { Text("Entendido") }
            }
        )
    }
}

@Composable
private fun WalletBalanceCard(
    balance: Int,
    debt: Int,
    lastUpdatedAt: String?,
    rechargeEnabled: Boolean,
    onRecharge: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(38.dp))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Saldo disponible", style = MaterialTheme.typography.labelLarge)
                    Text(
                        formatCoins(balance),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black
                    )
                    Text("monedas · no retirables", style = MaterialTheme.typography.bodySmall)
                    if (debt > 0) {
                        Text(
                            "Saldo neto: -${formatCoins(debt)} monedas por reembolso",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    lastUpdatedAt?.let {
                        Text(
                            "Actualizado ${it.replace('T', ' ').take(16)} UTC",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Button(
                onClick = onRecharge,
                enabled = rechargeEnabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Recargar") }
            Text(
                if (debt > 0) {
                    "Las próximas recargas compensarán primero la deuda del reembolso. Mientras exista deuda no se pueden enviar regalos."
                } else {
                    "Las monedas sirven para funciones y regalos dentro de Novvin. No representan dinero ni ganancias retirables."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnavailablePurchasesCard(
    title: String = "Compras no disponibles",
    body: String = "Esta instalación todavía no está conectada al catálogo publicado y a la verificación de Google Play. No se muestran precios ni se inicia ningún cobro."
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Black)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CoinPackageCard(
    coins: Int,
    price: String?,
    availability: String? = null,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(62.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.Start) {
                Text("${formatCoins(coins)} monedas", fontWeight = FontWeight.Black)
                Text(availability ?: "Producto consumible", style = MaterialTheme.typography.labelSmall)
            }
            Text(price ?: "No disponible", fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun SpendingProtectionCard(
    spentToday: Int,
    spentWeek: Int,
    historyComplete: Boolean,
    dailyLimit: Int,
    weeklyLimit: Int,
    onOpen: () -> Unit
) {
    val safeLimit = dailyLimit.coerceAtLeast(1)
    val progress = (spentToday.toFloat() / safeLimit.toFloat()).coerceIn(0f, 1f)
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Control de gasto", fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpen) { Text("Ver límites") }
            }
            if (historyComplete) {
                Text("Últimas 24 horas: ${formatCoins(spentToday)} de ${formatCoins(safeLimit)} monedas")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                if (weeklyLimit > 0) {
                    "Últimos 7 días: ${formatCoins(spentWeek)} de ${formatCoins(weeklyLimit)} monedas"
                } else {
                    "Últimos 7 días: ${formatCoins(spentWeek)} monedas · límite semanal no configurado"
                },
                style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("Faltan movimientos por cargar para calcular tu gasto. Usa Ver más en el historial.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "El servidor aplica el límite antes del débito; un rechazo no descuenta saldo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun String.toReadableTransactionType(): String = when (lowercase()) {
    "virtual_gift", "purchase" -> "Regalo enviado"
    "google_play_coin_purchase", "deposit" -> "Recarga de monedas"
    "refund" -> "Reembolso"
    else -> "Movimiento de monedas"
}

private fun String.toReadableTransactionStatus(): String = when (lowercase()) {
    "confirmed", "committed", "credited", "completed" -> "Confirmado"
    "pending" -> "Pendiente"
    "refunded" -> "Reembolsado"
    "reversed" -> "Revertido"
    "failed" -> "Fallido"
    else -> "Procesando"
}

private fun exportWalletHistory(context: Context, transactions: List<WalletTransaction>) {
    val text = buildString {
        appendLine("Historial de monedas Novvin")
        transactions.forEach { transaction ->
            append(transaction.createdAt?.take(19)?.replace('T', ' ') ?: "Sin fecha")
            append(" · ")
            append(transaction.description ?: transaction.type.toReadableTransactionType())
            append(" · ")
            append(transaction.amountDiamonds)
            append(" monedas · ")
            append(transaction.status.toReadableTransactionStatus())
            append(" · ref. ")
            appendLine(transaction.id)
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Historial de monedas Novvin")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Exportar historial"))
}
