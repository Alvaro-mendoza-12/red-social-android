package conectate.com.data.local.entity

import conectate.com.core.messaging.MessageStatusNormalizer
import conectate.com.core.messaging.mergeMessageReceiptState

private fun staleSend(synced: Boolean, action: String?, storedSynced: Boolean, storedStatus: String): Boolean =
    !synced && action == "send" && storedSynced &&
        MessageStatusNormalizer.normalize(storedStatus) in setOf("sent", "delivered", "read")

internal fun MessageEntity.withPersistedReceipts(local: MessageEntity?): MessageEntity {
    if (local == null || id != local.id || chat_id != local.chat_id || sender_id != local.sender_id) return this
    if (staleSend(is_synced, pending_action, local.is_synced, local.status)) return local
    val receipt = mergeMessageReceiptState(status, delivered_at, read_at, local.status, local.delivered_at, local.read_at)
    return copy(status = receipt.status, delivered_at = receipt.deliveredAt, read_at = receipt.readAt)
}

internal fun GroupMessageEntity.withPersistedReceipts(local: GroupMessageEntity?): GroupMessageEntity {
    if (local == null || id != local.id || group_id != local.group_id || sender_id != local.sender_id) return this
    if (staleSend(is_synced, pending_action, local.is_synced, local.status)) return local
    val receipt = mergeMessageReceiptState(status, delivered_at, read_at, local.status, local.delivered_at, local.read_at)
    return copy(status = receipt.status, delivered_at = receipt.deliveredAt, read_at = receipt.readAt)
}

internal fun CommunityMessageEntity.withPersistedReceipts(local: CommunityMessageEntity?): CommunityMessageEntity {
    if (local == null || id != local.id || community_id != local.community_id || sender_id != local.sender_id) return this
    if (staleSend(is_synced, pending_action, local.is_synced, local.status)) return local
    val receipt = mergeMessageReceiptState(status, delivered_at, read_at, local.status, local.delivered_at, local.read_at)
    return copy(status = receipt.status, delivered_at = receipt.deliveredAt, read_at = receipt.readAt)
}
