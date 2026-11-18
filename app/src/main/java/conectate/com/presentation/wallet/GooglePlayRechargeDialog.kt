package conectate.com.presentation.wallet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.LaunchedEffect
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
    minimumCoins: Int = 0,
    onDismiss: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val currentOwner by viewModel.accountSession.collectAsStateWithLifecycle()
    val owner = remember { currentOwner }
    val state by viewModel.refreshState.collectAsStateWithLifecycle()
    val wallet = if (owner.isCurrent() && owner.sameVisit(currentOwner) && state.owner?.sameVisit(owner) == true) state else WalletRefreshState(owner = null)
    val authorized = owner.isCurrent() && owner.sameVisit(currentOwner) && accountId.equals(owner.accountId, ignoreCase = true)
    val capabilities = wallet.capabilities
    val balance = if (wallet.confirmedAt != null) wallet.balance else 0
    val verifying by viewModel.billingVerifying.collectAsStateWithLifecycle()
    val message = wallet.billingMessage
    LaunchedEffect(currentOwner) { if (!authorized) onDismiss() }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityForBilling() }
    var playProducts by remember(owner, capabilities.enabled, capabilities.products) { mutableStateOf<List<PlayCoinProduct>>(emptyList()) }
    var catalogIssues by remember(owner, capabilities.enabled, capabilities.products) { mutableStateOf<List<PlayCatalogIssue>>(emptyList()) }
    var catalogResolved by remember(owner, capabilities.enabled, capabilities.products) { mutableStateOf(false) }

    val controller = remember(owner, authorized, capabilities.enabled, capabilities.products, accountId) {
        if (!authorized || !capabilities.enabled || accountId.isBlank()) null else GooglePlayBillingController(
            context = context,
            accountId = accountId,
            productIds = capabilities.products.map { it.productId }.toSet(),
            onProducts = {
                if (owner.isCurrent()) { playProducts = it; catalogResolved = true }
            },
            onCatalogIssues = { if (owner.isCurrent()) catalogIssues = it },
            onPurchased = { product, token -> if (owner.isCurrent()) viewModel.verifyGooglePlayPurchase(accountId, product, token) },
            onPending = {
                viewModel.showBillingMessage(
                    owner,
                    "Pago pendiente. No se acreditarán monedas hasta que Google Play confirme el cobro."
                )
            },
            onCanceled = {
                viewModel.showBillingMessage(owner, "Compra cancelada. No se realizó ningún cobro.")
            },
            onError = {
                if (owner.isCurrent()) catalogResolved = true
                viewModel.showBillingMessage(owner, it)
            }
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
                    if (wallet.confirmedAt == null) "Saldo sin confirmar" else "$balance disponibles",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (minimumCoins > 0) {
                    Text(
                        "Necesitas al menos $minimumCoins monedas adicionales",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    wallet.billingError != null -> {
                        Text(wallet.billingError, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = viewModel::refresh) { Text("Reintentar compras") }
                    }
                    !capabilities.enabled || accountId.isBlank() -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Compras no disponibles", fontWeight = FontWeight.Black)
                                Text(
                                    if (accountId.isBlank()) {
                                        "Inicia sesión para consultar los paquetes disponibles para tu cuenta."
                                    } else {
                                        "Esta instalación todavía no tiene habilitadas las compras de monedas. No se iniciará ningún cobro."
                                    }
                                )
                            }
                        }
                        Text(
                            "Se habilitarán únicamente cuando Google Play publique el catálogo localizado y el servidor pueda verificar cada compra.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    playProducts.isEmpty() && !catalogResolved -> {
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
                    catalogResolved -> {
                        if (playProducts.isEmpty()) {
                            Text("No se pudieron obtener precios disponibles para esta cuenta.")
                            OutlinedButton(onClick = { catalogResolved = false; controller?.start() }, enabled = controller != null) { Text("Volver a consultar precios") }
                        }
                        Text(
                            "El precio mostrado es el precio localizado que entrega Google Play.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(capabilities.products.sortedBy { it.coins }, key = { it.productId }) { coinProduct ->
                                val playProduct = playProducts.firstOrNull { it.productId == coinProduct.productId }
                                val issue = catalogIssues.firstOrNull { it.productId == coinProduct.productId }
                                val recommendedProductId = capabilities.products
                                    .filter { it.coins >= minimumCoins.coerceAtLeast(1) }
                                    .minByOrNull { it.coins }
                                    ?.productId
                                val isRecommended = minimumCoins > 0 && coinProduct.productId == recommendedProductId
                                OutlinedButton(
                                    onClick = {
                                        val hostActivity = activity
                                        if (hostActivity == null) {
                                            viewModel.showBillingMessage(owner, "No se pudo abrir Google Play en esta pantalla.")
                                        } else {
                                            if (owner.isCurrent()) controller?.launch(hostActivity, coinProduct.productId)
                                        }
                                    },
                                    enabled = authorized && playProduct != null && !verifying,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${coinProduct.coins} monedas", fontWeight = FontWeight.Bold)
                                            Text(
                                                issue?.userFacingDescription()
                                                    ?: if (isRecommended) "Recomendado para completar el regalo" else "Producto consumible",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        Text(playProduct?.formattedPrice ?: "No disponible", fontWeight = FontWeight.Black)
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
            onDismissRequest = { viewModel.clearBillingMessage(owner) },
            title = { Text("Google Play") },
            text = { Text(value) },
            confirmButton = {
                Button(onClick = { viewModel.clearBillingMessage(owner) }) { Text("Entendido") }
            }
        )
    }
}

internal tailrec fun Context.findActivityForBilling(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityForBilling()
    else -> null
}
