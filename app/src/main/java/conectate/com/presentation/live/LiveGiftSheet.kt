package conectate.com.presentation.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.LiveBattleState
import conectate.com.data.model.LiveBattleStatus
import conectate.com.data.model.LiveStream
import conectate.com.presentation.wallet.GiftSelectionDialog

/** LIVE uses the same preview, confirmation, billing and receipt flow as the wallet. */
@Composable
internal fun LiveGiftSheet(
    stream: LiveStream,
    battle: LiveBattleState,
    onDismiss: () -> Unit,
    onRecharge: (Int) -> Unit,
    onGiftSent: (GiftReceipt) -> Unit,
) {
    val opponentId = battle.opponentUserId.takeIf {
        battle.status == LiveBattleStatus.ACTIVE && it.isNotBlank()
    }
    var selectedId by remember(stream.id, opponentId) { mutableStateOf(stream.hostUserId) }
    val receiverName = if (selectedId == opponentId) {
        battle.opponentName.ifBlank { "Invitado" }
    } else stream.hostName.ifBlank { "Anfitrión" }

    GiftSelectionDialog(
        receiverId = selectedId,
        receiverName = receiverName,
        liveStreamId = stream.id,
        onDismiss = onDismiss,
        onRecharge = onRecharge,
        onGiftSent = onGiftSent,
        receiverSelector = { enabled ->
            if (opponentId != null && opponentId != stream.hostUserId) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedId == stream.hostUserId,
                        enabled = enabled,
                        onClick = { selectedId = stream.hostUserId },
                        label = { Text(stream.hostName.ifBlank { "Anfitrión" }) },
                    )
                    FilterChip(
                        selected = selectedId == opponentId,
                        enabled = enabled,
                        onClick = { selectedId = opponentId },
                        label = { Text(battle.opponentName.ifBlank { "Invitado" }) },
                    )
                }
            }
        },
    )
}
