package conectate.com.presentation.wallet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
        title = { Text("Recargar monedas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !capabilities.enabled -> {
                        Text(
                            "Las compras están desactivadas en este entorno. Para evitar cobros sin respaldo, primero debe configurarse Play Console y la verificación del servidor."
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
                    else -> capabilities.products.forEach { coinProduct ->
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
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${coinProduct.coins} monedas", fontWeight = FontWeight.Bold)
                                    Text(playProduct.formattedPrice)
                                }
                            }
                        }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
