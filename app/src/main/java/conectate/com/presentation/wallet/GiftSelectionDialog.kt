package conectate.com.presentation.wallet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.R
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.GiftCategory
import conectate.com.data.model.GiftTier
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.category
import conectate.com.data.model.tier
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftSelectionDialog(
    receiverId: String,
    receiverName: String,
    liveStreamId: String,
    onDismiss: () -> Unit,
    onRecharge: (minimumCoins: Int) -> Unit,
    onGiftSent: (GiftReceipt) -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
    receiverSelector: @Composable (enabled: Boolean) -> Unit = {},
) {
    val balance by viewModel.coinBalance.collectAsStateWithLifecycle()
    val gifts by viewModel.availableGifts.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val sendingGiftId by viewModel.sendingGiftId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val receipt by viewModel.lastReceipt.collectAsStateWithLifecycle()
    val billingCapabilities by viewModel.billingCapabilities.collectAsStateWithLifecycle()
    var confirmationGift by remember { mutableStateOf<VirtualGift?>(null) }
    var previewGift by remember { mutableStateOf<VirtualGift?>(null) }
    var isPublic by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf<GiftCategory?>(null) }
    val visibleGifts = remember(gifts, selectedCategory) {
        gifts.filter { selectedCategory == null || it.category() == selectedCategory }
    }

    LaunchedEffect(receipt?.transactionId) {
        val committed = receipt ?: return@LaunchedEffect
        onGiftSent(committed)
        viewModel.consumeReceipt()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { if (sendingGiftId == null) onDismiss() },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Image(
                painter = painterResource(R.drawable.gift_catalog_backdrop_v2),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.28f,
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f))
            )
            Column(
                modifier = Modifier
                    .matchParentSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
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

            receiverSelector(sendingGiftId == null)

            Button(onClick = { onRecharge(0) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Recargar monedas · elegir monto")
            }

            HorizontalDivider()

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("Todos") }
                    )
                }
                lazyRowItems(GiftCategory.values().toList()) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.displayName()) }
                    )
                }
            }

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
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleGifts, key = { it.id }) { gift ->
                        GiftCatalogCard(
                            gift = gift,
                            enabled = sendingGiftId == null &&
                                gift.priceDiamonds <= billingCapabilities.giftLimits.maxCoinsPerGift,
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
            Text(
                "Protección activa: máximo ${billingCapabilities.giftLimits.maxCoinsPerGift} monedas por regalo; el servidor también aplica límites diarios y de frecuencia.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
        }
    }

    confirmationGift?.let { gift ->
        val retrying = viewModel.hasPendingGift(receiverId, liveStreamId, gift.id, isPublic)
        val isHighValue = gift.priceDiamonds >=
            billingCapabilities.giftLimits.highValueConfirmationCoins
        var highValueAcknowledged by remember(gift.id) { mutableStateOf(false) }
        var safetyCountdown by remember(gift.id) { mutableIntStateOf(if (isHighValue) 3 else 0) }
        LaunchedEffect(gift.id, isHighValue) {
            while (safetyCountdown > 0) {
                delay(1_000)
                safetyCountdown -= 1
            }
        }
        AlertDialog(
            onDismissRequest = { if (sendingGiftId == null) confirmationGift = null },
            title = { Text("Confirmar regalo") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GiftArtwork(gift, Modifier.size(112.dp))
                    Text(gift.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    gift.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Para: $receiverName", fontWeight = FontWeight.Bold)
                    Text(if (isPublic) "Visible en el directo" else "Envío privado")
                    Text("Costo: ${gift.priceDiamonds} monedas")
                    Text(
                        if (retrying) "Comprobaremos el envío anterior sin duplicar el cargo."
                        else "Saldo después: ${(balance - gift.priceDiamonds).coerceAtLeast(0)} monedas",
                        fontWeight = FontWeight.Bold
                    )
                    if (balance < gift.priceDiamonds && !retrying) {
                        Text("Tu saldo no alcanza para este regalo.", color = MaterialTheme.colorScheme.error)
                    }
                    if (isHighValue) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Confirmación reforzada", fontWeight = FontWeight.Black)
                                Text(
                                    if (safetyCountdown > 0) {
                                        "Puedes cancelar sin cargo. Revisa los datos durante $safetyCountdown s."
                                    } else {
                                        "La revisión terminó. El cargo sólo se realiza al pulsar Enviar."
                                    }
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = highValueAcknowledged,
                                        onCheckedChange = { highValueAcknowledged = it },
                                        enabled = safetyCountdown == 0 && sendingGiftId == null
                                    )
                                    Text("Entiendo el costo y el destinatario")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = sendingGiftId == null &&
                        (retrying || gift.priceDiamonds <= billingCapabilities.giftLimits.maxCoinsPerGift) &&
                        (!isHighValue || (safetyCountdown == 0 && highValueAcknowledged)),
                    onClick = {
                        if (balance < gift.priceDiamonds && !retrying) {
                            onRecharge((gift.priceDiamonds - balance).coerceAtLeast(1))
                        } else {
                            viewModel.sendGift(receiverId, liveStreamId, gift, isPublic)
                        }
                    }
                ) {
                    if (sendingGiftId == gift.id) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Enviando…")
                    } else {
                        Text(if (retrying) "Comprobar envío" else if (balance < gift.priceDiamonds) "Recargar para enviar" else "Enviar regalo")
                    }
                }
            },
            dismissButton = {
                Row {
                    OutlinedButton(
                        onClick = { previewGift = gift },
                        enabled = sendingGiftId == null
                    ) {
                        Text("Ver animación")
                    }
                    TextButton(onClick = { confirmationGift = null }, enabled = sendingGiftId == null) {
                        Text("Cancelar")
                    }
                }
            }
        )
    }

    previewGift?.let { gift ->
        GiftPreviewDialog(gift = gift, onDismiss = { previewGift = null })
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
                { TextButton(onClick = { viewModel.clearError(); onRecharge(1) }) { Text("Recargar") } }
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
            GiftArtwork(gift, Modifier.size(72.dp))
            Text(gift.name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            Text("${gift.priceDiamonds} monedas", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(gift.tier().displayName(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun GiftCategory.displayName(): String = when (this) {
    GiftCategory.WELCOME -> "Saludos"
    GiftCategory.ENERGY -> "Energía"
    GiftCategory.CELEBRATION -> "Celebración"
    GiftCategory.PREMIUM -> "Premium"
}

private fun GiftTier.displayName(): String = when (this) {
    GiftTier.MICRO -> "Animación breve"
    GiftTier.FEATURED -> "Animación destacada"
    GiftTier.CELEBRATION -> "Celebración completa"
}

@Composable
private fun GiftPreviewDialog(gift: VirtualGift, onDismiss: () -> Unit) {
    var replay by remember(gift.id) { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vista previa") },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().height(360.dp),
                contentAlignment = Alignment.Center
            ) {
                GiftAnimationStage(
                    gift = gift,
                    senderLabel = "",
                    runKey = "${gift.id}:$replay",
                    preview = true,
                    showCaption = false
                )
            }
        },
        confirmButton = {
            Button(onClick = { replay += 1 }) { Text("Repetir") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}
