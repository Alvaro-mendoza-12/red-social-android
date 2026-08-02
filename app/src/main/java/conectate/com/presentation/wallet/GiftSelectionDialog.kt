package conectate.com.presentation.wallet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.VirtualGift

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftSelectionDialog(
    receiverId: String,
    receiverName: String,
    liveStreamId: String,
    onDismiss: () -> Unit,
    onRecharge: () -> Unit,
    onGiftSent: (GiftReceipt) -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val balance by viewModel.coinBalance.collectAsStateWithLifecycle()
    val gifts by viewModel.availableGifts.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val sendingGiftId by viewModel.sendingGiftId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val receipt by viewModel.lastReceipt.collectAsStateWithLifecycle()
    var confirmationGift by remember { mutableStateOf<VirtualGift?>(null) }
    var isPublic by remember { mutableStateOf(true) }

    LaunchedEffect(receipt?.transactionId) {
        val committed = receipt ?: return@LaunchedEffect
        onGiftSent(committed)
        viewModel.consumeReceipt()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { if (sendingGiftId == null) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Enviar regalo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("Para $receiverName", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
                    Text(
                        "$balance monedas",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedButton(onClick = onRecharge, modifier = Modifier.fillMaxWidth()) {
                Text("Recargar con Google Play")
            }

            Divider()

            when {
                loading && gifts.isEmpty() -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                gifts.isEmpty() -> Column(
                    Modifier.fillMaxWidth().height(220.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("No fue posible cargar el catálogo", fontWeight = FontWeight.Bold)
                    TextButton(onClick = viewModel::refresh) { Text("Reintentar") }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(gifts, key = { it.id }) { gift ->
                        GiftCatalogCard(
                            gift = gift,
                            enabled = sendingGiftId == null,
                            onClick = { confirmationGift = gift }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Mostrar en el directo", fontWeight = FontWeight.Bold)
                    Text("Si lo desactivas, el regalo será privado.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = isPublic, onCheckedChange = { isPublic = it }, enabled = sendingGiftId == null)
            }
        }
    }

    confirmationGift?.let { gift ->
        AlertDialog(
            onDismissRequest = { if (sendingGiftId == null) confirmationGift = null },
            title = { Text("Confirmar regalo") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GiftArtwork(gift.id, Modifier.size(112.dp))
                    Text(gift.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("Costo: ${gift.priceDiamonds} monedas")
                    Text(
                        "Saldo después: ${(balance - gift.priceDiamonds).coerceAtLeast(0)} monedas",
                        fontWeight = FontWeight.Bold
                    )
                    if (balance < gift.priceDiamonds) {
                        Text("Tu saldo no alcanza para este regalo.", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = sendingGiftId == null && balance >= gift.priceDiamonds,
                    onClick = {
                        viewModel.sendGift(receiverId, liveStreamId, gift, isPublic)
                    }
                ) {
                    if (sendingGiftId == gift.id) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Enviando…")
                    } else {
                        Text("Enviar regalo")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmationGift = null }, enabled = sendingGiftId == null) {
                    Text("Cancelar")
                }
            }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("No se pudo enviar") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text("Entendido") }
            },
            dismissButton = if (message.contains("Saldo insuficiente", ignoreCase = true)) {
                { TextButton(onClick = { viewModel.clearError(); onRecharge() }) { Text("Recargar") } }
            } else null
        )
    }
}

@Composable
private fun GiftCatalogCard(
    gift: VirtualGift,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            GiftArtwork(gift.id, Modifier.size(72.dp))
            Text(gift.name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            Text("${gift.priceDiamonds} monedas", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
