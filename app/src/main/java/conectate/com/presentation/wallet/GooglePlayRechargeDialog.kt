package conectate.com.presentation.wallet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun GooglePlayRechargeDialog(
    accountId: String,
    onDismiss: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val capabilities by viewModel.billingCapabilities.collectAsStateWithLifecycle()
    val balance by viewModel.coinBalance.collectAsStateWithLifecycle()
    val verifying by viewModel.billingVerifying.collectAsStateWithLifecycle()
    val message by viewModel.billingMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var playProducts by remember(capabilities.products) { mutableStateOf<List<PlayCoinProduct>>(emptyList()) }

    val controller = remember(capabilities.enabled, capabilities.products, accountId) {
        if (!capabilities.enabled || accountId.isBlank()) null else GooglePlayBillingController(
            context = context,
            accountId = accountId,
            productIds = capabilities.products.map { it.productId }.toSet(),
            onProducts = { playProducts = it },
            onPurchased = viewModel::verifyGooglePlayPurchase,
            onPending = {
                viewModel.showBillingMessage(
                    "Pago pendiente. No se acreditarán monedas hasta que Google Play confirme el cobro."
                )
            },
            onError = viewModel::showBillingMessage
        )
    }

    DisposableEffect(controller) {
        controller?.start()
        onDispose { controller?.close() }
    }

    AlertDialog(
        onDismissRequest = { if (!verifying) onDismiss() },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Recargar monedas")
                Text(
                    "$balance disponibles",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !capabilities.enabled -> {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("DEMO · pagos desactivados", fontWeight = FontWeight.Black)
                                Text(
                                    "Estos paquetes sólo muestran la distribución prevista. No tienen precio ni pueden generar un cobro."
                                )
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(DEMO_COIN_PACKAGES) { coins ->
                                DemoCoinPackageCard(coins)
                            }
                        }
                        Text(
                            "Para activar compras deben existir los productos en Play Console y la verificación transaccional del servidor.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    playProducts.isEmpty() -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                        }
                        Text(
                            "Consultando productos consumibles en Google Play…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    else -> {
                        Text(
                            "El precio mostrado es el precio localizado que entrega Google Play.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(capabilities.products, key = { it.productId }) { coinProduct ->
                                val playProduct = playProducts.firstOrNull { it.productId == coinProduct.productId }
                                if (playProduct != null) {
                                    OutlinedButton(
                                        onClick = {
                                            val hostActivity = activity
                                            if (hostActivity == null) {
                                                viewModel.showBillingMessage("No se encontró una actividad Android para abrir Google Play.")
                                            } else {
                                                controller?.launch(hostActivity, coinProduct.productId)
                                            }
                                        },
                                        enabled = !verifying,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("${coinProduct.coins} monedas", fontWeight = FontWeight.Bold)
                                                Text("Producto consumible", style = MaterialTheme.typography.labelSmall)
                                            }
                                            Text(playProduct.formattedPrice, fontWeight = FontWeight.Black)
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "Las monedas sólo se acreditan después de que el servidor verifica la compra.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) { Text("Cerrar") }
        }
    )

    message?.let { value ->
        AlertDialog(
            onDismissRequest = viewModel::clearBillingMessage,
            title = { Text("Google Play") },
            text = { Text(value) },
            confirmButton = {
                Button(onClick = viewModel::clearBillingMessage) { Text("Entendido") }
            }
        )
    }
}

private val DEMO_COIN_PACKAGES = listOf(70, 140, 350, 700, 1_400, 3_500, 7_000, 14_000)

@Composable
private fun DemoCoinPackageCard(coins: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$coins monedas", fontWeight = FontWeight.Bold)
            Text("Sin precio · DEMO", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
