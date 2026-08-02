package conectate.com.presentation.wallet

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinsAndPaymentsScreen(
    navController: NavController,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val balance by viewModel.coinBalance.collectAsStateWithLifecycle()
    val capabilities by viewModel.billingCapabilities.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var showRecharge by remember { mutableStateOf(false) }
    val displayedCoinAmounts = capabilities.products.map { it.coins }.ifEmpty {
        listOf(70, 140, 350, 700, 1_400, 3_500, 7_000, 14_000)
    }.distinct().sorted()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monedas y pagos") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
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
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Saldo disponible", style = MaterialTheme.typography.labelLarge)
                            Text("$balance monedas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { showRecharge = true },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Recargar y elegir monto")
                }
            }

            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paquetes disponibles", fontWeight = FontWeight.Bold)
                        if (!capabilities.enabled) {
                            Text(
                                "DEMO · sin precio ni cobro. La compra se habilita al conectar Play Console y la verificación del servidor.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        displayedCoinAmounts.forEach { coins ->
                            Text(
                                if (capabilities.enabled) {
                                    "$coins monedas · precio localizado en Google Play"
                                } else {
                                    "$coins monedas · demostración"
                                }
                            )
                        }
                        Text(
                            "El cobro sólo se acredita después de la confirmación de Google Play.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://play.google.com/store/account/paymentmethods") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Gestionar métodos en Google Play")
                }
            }
            item {
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://support.google.com/googleplay/answer/2479637") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ayuda con compras y reembolsos")
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Historial de monedas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            if (transactions.isEmpty()) {
                item {
                    Text(
                        if (loading) "Actualizando movimientos…" else "Todavía no hay movimientos para mostrar.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(transactions, key = { it.id }) { transaction ->
                    ListItem(
                        headlineContent = {
                            Text(transaction.description ?: transaction.type.toReadableTransactionType(), fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = {
                            Text(buildString {
                                append("Saldo: ${transaction.balanceAfterDiamonds}")
                                transaction.createdAt?.take(16)?.replace('T', ' ')?.let { append(" · $it") }
                            })
                        },
                        trailingContent = {
                            Text(
                                text = if (transaction.amountDiamonds > 0) "+${transaction.amountDiamonds}" else transaction.amountDiamonds.toString(),
                                color = if (transaction.amountDiamonds >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Black
                            )
                        }
                    )
                }
            }
        }
    }

    if (showRecharge) {
        GooglePlayRechargeDialog(
            accountId = viewModel.currentAccountId(),
            onDismiss = { showRecharge = false },
            viewModel = viewModel
        )
    }
}

private fun String.toReadableTransactionType(): String = when (lowercase()) {
    "virtual_gift", "purchase" -> "Regalo enviado"
    "google_play_coin_purchase", "deposit" -> "Recarga de monedas"
    "refund" -> "Reembolso"
    else -> "Movimiento de monedas"
}
