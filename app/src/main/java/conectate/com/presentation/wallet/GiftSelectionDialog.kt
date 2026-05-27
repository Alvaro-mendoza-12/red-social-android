package conectate.com.presentation.wallet

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.VirtualGift

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftSelectionDialog(
    receiverId: String,
    receiverName: String,
    onDismiss: () -> Unit,
    onGiftSent: () -> Unit,
    liveStreamId: String? = null,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val balance by viewModel.coinBalance.collectAsStateWithLifecycle()
    val gifts by viewModel.availableGifts.collectAsStateWithLifecycle()
    var selectedGift by remember { mutableStateOf<VirtualGift?>(null) }
    var isSending by remember { mutableStateOf(false) }
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Enviar regalo a $receiverName",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            AssistChip(
                onClick = {},
                label = { Text("Saldo disponible: $balance") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.MonetizationOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (gifts.isEmpty()) {
                Text(
                    text = "La tienda de regalos está vacía o no se pudo sincronizar con Supabase.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(gifts, key = { it.id }) { gift ->
                        val isSelected = selectedGift?.id == gift.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedGift = gift },
                            shape = RoundedCornerShape(12.dp),
                            border = if (isSelected) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    modifier = Modifier.size(52.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = gift.displayIcon(),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = gift.name,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MonetizationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = gift.priceDiamonds.toString(),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    selectedGift?.let { gift ->
                        isSending = true
                        viewModel.sendGift(
                            receiverId = receiverId,
                            amount = gift.priceDiamonds,
                            giftId = gift.id,
                            liveStreamId = liveStreamId,
                            onSuccess = {
                                Toast.makeText(context, "¡Regalo enviado!", Toast.LENGTH_SHORT).show()
                                isSending = false
                                onGiftSent()
                                onDismiss()
                            },
                            onError = { error ->
                                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                isSending = false
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedGift != null && !isSending && balance >= (selectedGift?.priceDiamonds ?: 0)
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                } else {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enviar Regalo")
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun VirtualGift.displayIcon(): ImageVector {
    val normalized = name.lowercase()
    return when {
        "rosa" in normalized -> Icons.Filled.LocalFlorist
        "corazon" in normalized || "corazón" in normalized -> Icons.Filled.Favorite
        "trofeo" in normalized || "corona" in normalized -> Icons.Filled.EmojiEvents
        "fuego" in normalized -> Icons.Filled.Celebration
        "estrella" in normalized -> Icons.Filled.Stars
        "cohete" in normalized -> Icons.Filled.RocketLaunch
        else -> Icons.Filled.CardGiftcard
    }
}
