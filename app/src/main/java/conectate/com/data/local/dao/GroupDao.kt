package conectate.com.data.local.dao

import androidx.room.*
import conectate.com.data.local.entity.GroupEntity
import conectate.com.data.local.entity.GroupMemberEntity
import conectate.com.data.local.entity.GroupMessageEntity
import conectate.com.data.local.entity.withPersistedReceipts
import kotlinx.coroutines.flow.Flow

data class GroupUnreadCountRow(
    val groupId: String,
    val unreadCount: Int
)

@Dao
interface GroupDao {
    @Query("UPDATE groups SET unread_count = MAX(0, unread_count - :count) WHERE id = :groupId")
    suspend fun subtractReadMessages(groupId: String, count: Int)
    // GROUPS
    @Query("SELECT * FROM groups WHERE is_deleted = 0 ORDER BY COALESCE(last_message_at, created_at) DESC")
    fun getAllGroupsFlow(): Flow<List<GroupEntity>>

    @Query(
        "SELECT groups.* FROM groups " +
            "INNER JOIN group_members ON group_members.group_id = groups.id " +
            "WHERE groups.is_deleted = 0 AND group_members.user_id = :userId " +
            "ORDER BY COALESCE(groups.last_message_at, groups.created_at) DESC"
    )
    fun getGroupsForUserFlow(userId: String): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE is_deleted = 0 ORDER BY COALESCE(last_message_at, created_at) DESC")
    suspend fun getAllGroupsSync(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :groupId AND is_deleted = 0")
    suspend fun getGroupById(groupId: String): GroupEntity?

    @Query("UPDATE groups SET is_member = :isMember WHERE id = :groupId")
    suspend fun updateMembership(groupId: String, isMember: Boolean)
    
    @Query("SELECT * FROM groups WHERE id = :groupId AND is_deleted = 0")
    fun getGroupByIdFlow(groupId: String): Flow<GroupEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroupsIgnore(groups: List<GroupEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroupIgnore(group: GroupEntity): Long

    @Update
    suspend fun updateGroups(groups: List<GroupEntity>)

    @Transaction
    suspend fun upsertGroups(groups: List<GroupEntity>) {
        val insertResults = insertGroupsIgnore(groups)
        val toUpdate = mutableListOf<GroupEntity>()
        
        for (i in insertResults.indices) {
            if (insertResults[i] == -1L) {
                toUpdate.add(groups[i])
            }
        }
        
        if (toUpdate.isNotEmpty()) {
            updateGroups(toUpdate)
        }
    }

    @Transaction
    suspend fun upsertGroup(group: GroupEntity) {
        var result = insertGroupIgnore(group)
        if (result == -1L) {
            updateGroup(group)
        }
    }

    @Transaction
    suspend fun saveCreatedGroup(group: GroupEntity, members: List<GroupMemberEntity>, requireCurrent: () -> Unit) {
        requireCurrent()
        val previous = getGroupById(group.id)
        upsertGroup(group.copy(last_message = previous?.last_message ?: group.last_message,
            last_message_at = previous?.last_message_at ?: group.last_message_at, unread_count = previous?.unread_count ?: group.unread_count))
        clearCreatedGroupMembers(group.id)
        insertMembers(members)
        requireCurrent() // Throwing rolls back both rows if the session changed during E/S.
    }

    @Update
    suspend fun updateGroup(group: GroupEntity)

    @Query("UPDATE groups SET last_message = :lastMsg, last_message_at = :lastTime WHERE id = :groupId")
    suspend fun updateMetadata(groupId: String, lastMsg: String?, lastTime: String?)

    // Concurrent sends may be acknowledged out of order. Keep the newest preview.
    @Query("""
        UPDATE groups SET last_message = :lastMsg, last_message_at = :lastTime
        WHERE id = :groupId AND (
            last_message_at IS NULL OR
            COALESCE(julianday(:lastTime), 0) >= COALESCE(julianday(last_message_at), 0)
        )
    """)
    suspend fun updateMetadataIfNewer(groupId: String, lastMsg: String?, lastTime: String?)

    @Query("UPDATE groups SET unread_count = 0 WHERE id = :groupId")
    suspend fun resetUnreadCount(groupId: String)

    @Query("UPDATE group_messages SET status='read',read_at=COALESCE(read_at,:through) WHERE group_id=:id AND LOWER(sender_id)!=LOWER(:account) AND julianday(created_at)<=julianday(:through) AND LOWER(COALESCE(status,'')) IN ('sent','delivered','received')")
    suspend fun markReadThrough(id: String, account: String, through: String)

    @Query("UPDATE groups SET unread_count=(SELECT COUNT(*) FROM group_messages WHERE group_id=:id AND LOWER(sender_id)!=LOWER(:account) AND LOWER(COALESCE(status,'')) IN ('sent','delivered','received')) WHERE id=:id")
    suspend fun reconcileUnreadCount(id: String, account: String)

    @Query("UPDATE groups SET unread_count = unread_count + 1 WHERE id = :groupId")
    suspend fun incrementUnreadCount(groupId: String)

    @Query("UPDATE groups SET unread_count = unread_count + :amount WHERE id = :groupId")
    suspend fun incrementUnreadCount(groupId: String, amount: Int)

    @Query("UPDATE groups SET is_deleted = 1 WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("UPDATE groups SET is_member = 0 WHERE id NOT IN (:validIds)")
    suspend fun markOthersAsNotMembers(validIds: List<String>)

    @Query("UPDATE groups SET is_deleted = 1 WHERE id IN (:ids)")
    suspend fun markAsDeletedGlobally(ids: List<String>)

    @Query("UPDATE groups SET is_deleted = 1 WHERE id = :groupId")
    suspend fun deleteGroupGlobally(groupId: String)


    // MEMBERS
    @Query("DELETE FROM group_members WHERE group_id = :groupId")
    suspend fun clearCreatedGroupMembers(groupId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<GroupMemberEntity>)

    @Query("SELECT * FROM group_members WHERE group_id = :groupId AND role != 'former_member'")
    fun getMembersFlow(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT joined_at FROM group_members WHERE group_id = :roomId AND user_id = :userId")
    suspend fun getMemberJoinedAt(roomId: String, userId: String): String?

    @Query("DELETE FROM group_members WHERE group_id = :groupId AND user_id = :userId")
    suspend fun removeMember(groupId: String, userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM group_members WHERE group_id = :groupId AND user_id = :userId AND role != 'former_member')")
    fun isUserMemberFlow(groupId: String, userId: String): Flow<Boolean>

    @Query("DELETE FROM group_members WHERE user_id = :userId AND role != 'former_member' AND group_id NOT IN (:validIds)")
    suspend fun deleteMembershipsExcept(userId: String, validIds: List<String>)

    @Query("DELETE FROM group_members WHERE user_id = :userId AND role != 'former_member'")
    suspend fun deleteAllMembershipsForUser(userId: String)

    @Query("DELETE FROM group_members WHERE user_id = :userId AND role != 'former_member' AND group_id NOT IN (:validGroupIds)")
    suspend fun deleteUserMembershipsNotIn(userId: String, validGroupIds: List<String>)

    @Query("SELECT role FROM group_members WHERE group_id = :groupId AND user_id = :userId AND role != 'former_member'")
    fun getUserRoleFlow(groupId: String, userId: String): Flow<String?>

    @Query("SELECT role FROM group_members WHERE group_id = :groupId AND user_id = :userId AND role != 'former_member'")
    suspend fun getUserRoleSync(groupId: String, userId: String): String?

    // MESSAGES
    @Query("SELECT * FROM group_messages WHERE group_id = :groupId ORDER BY created_at ASC")
    fun getMessagesFlow(groupId: String): Flow<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_messages WHERE group_id = :groupId ORDER BY created_at ASC")
    suspend fun getMessagesSync(groupId: String): List<GroupMessageEntity>

    @Query("DELETE FROM group_messages WHERE group_id = :roomId AND id IN (:ids) AND is_synced = 1 AND pending_action IS NULL")
    suspend fun deleteInaccessibleHistory(roomId: String, ids: List<String>)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessagesRaw(messages: List<GroupMessageEntity>)

    @Query("SELECT * FROM group_messages WHERE id IN (:messageIds)")
    suspend fun getMessagesByIds(messageIds: List<String>): List<GroupMessageEntity>

    @Transaction
    suspend fun insertMessages(messages: List<GroupMessageEntity>) { persistMessages(messages) }

    @Transaction
    suspend fun persistMessages(messages: List<GroupMessageEntity>): List<GroupMessageEntity> {
        if (messages.isEmpty()) return emptyList()
        val latest = messages.map { it.id }.distinct().chunked(800)
            .flatMap { getMessagesByIds(it) }.associateBy { it.id }.toMutableMap()
        val merged = messages.map { incoming -> incoming.withPersistedReceipts(latest[incoming.id]).also { latest[it.id] = it } }
        insertMessagesRaw(merged)
        return merged
    }

    @Transaction
    suspend fun insertMessagesOwned(messages: List<GroupMessageEntity>, requireCurrent: () -> Unit) {
        persistMessagesOwned(messages, requireCurrent)
    }

    @Transaction
    suspend fun persistMessagesOwned(messages: List<GroupMessageEntity>, requireCurrent: () -> Unit): List<GroupMessageEntity> {
        requireCurrent()
        val merged = persistMessages(messages)
        requireCurrent()
        return merged
    }

    @Transaction
    suspend fun updateMetadataOwned(groupId: String, lastMsg: String?, lastTime: String?, requireCurrent: () -> Unit) {
        requireCurrent()
        updateMetadataIfNewer(groupId, lastMsg, lastTime)
        requireCurrent()
    }

    @Transaction
    suspend fun updateMessageSyncStatusOwned(messageId: String, status: String, isSynced: Boolean, pendingAction: String?, requireCurrent: () -> Unit) {
        requireCurrent()
        updateMessageSyncStatus(messageId, status, isSynced, pendingAction)
        requireCurrent()
    }

    @Query("SELECT id FROM group_messages WHERE group_id = :groupId")
    suspend fun getGroupMessageIds(groupId: String): List<String>

    @Query("SELECT * FROM group_messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): GroupMessageEntity?

    @Query("SELECT * FROM group_messages WHERE is_synced = 0 AND pending_action = 'send' AND deleted_for_all = 0 AND LOWER(TRIM(sender_id)) = LOWER(TRIM(:senderId)) ORDER BY created_at ASC, id ASC LIMIT :limit")
    suspend fun getPendingOutgoingMessages(senderId: String, limit: Int): List<GroupMessageEntity>

    @Query("""
        UPDATE group_messages SET status = CASE
            WHEN LOWER(status) = 'read' OR NULLIF(TRIM(read_at), '') IS NOT NULL THEN 'read'
            WHEN LOWER(status) IN ('delivered','received') OR NULLIF(TRIM(delivered_at), '') IS NOT NULL THEN 'delivered'
            ELSE :status END, is_synced = :isSynced, pending_action = :pendingAction
        WHERE id = :messageId AND NOT (:isSynced = 0 AND COALESCE(:pendingAction, '') = 'send' AND is_synced = 1
            AND (LOWER(status) IN ('sent','delivered','received','read') OR NULLIF(TRIM(read_at), '') IS NOT NULL OR NULLIF(TRIM(delivered_at), '') IS NOT NULL))
    """)
    suspend fun updateMessageSyncStatus(messageId: String, status: String, isSynced: Boolean, pendingAction: String?)

    @Query("UPDATE group_messages SET retry_count = retry_count + 1, last_retry_at = :lastRetryAt, status = :status WHERE id = :messageId AND is_synced = 0 AND NULLIF(TRIM(read_at), '') IS NULL AND NULLIF(TRIM(delivered_at), '') IS NULL AND LOWER(status) NOT IN ('read','delivered','received')")
    suspend fun markRetryAttempt(messageId: String, lastRetryAt: String, status: String = "failed")
    @Query("UPDATE group_messages SET status = 'read', read_at = :now, delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END WHERE group_id = :groupId AND sender_id != :currentUserId AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received')")
    suspend fun markAllAsRead(groupId: String, now: String, currentUserId: String)

    @Query("UPDATE group_messages SET status = 'delivered', delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END WHERE group_id = :groupId AND sender_id != :currentUserId AND LOWER(COALESCE(status, '')) = 'sent'")
    suspend fun markAllAsDelivered(groupId: String, now: String, currentUserId: String)

    @Query(
        "UPDATE group_messages " +
            "SET status = 'read', " +
            "read_at = :now, " +
            "delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END " +
            "WHERE group_id = :groupId " +
            "AND sender_id != :currentUserId " +
            "AND id IN (:messageIds) " +
            "AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received')"
    )
    suspend fun markMessagesAsReadByIds(
        groupId: String,
        now: String,
        currentUserId: String,
        messageIds: List<String>
    )

    @Query(
        "SELECT COUNT(*) FROM group_messages " +
            "WHERE group_id = :groupId " +
            "AND sender_id != :currentUserId " +
            "AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received')"
    )
    suspend fun countUnreadIncoming(groupId: String, currentUserId: String): Int

    @Query(
        "SELECT group_id AS groupId, COUNT(*) AS unreadCount " +
            "FROM group_messages " +
            "WHERE sender_id != :currentUserId " +
            "AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received') " +
            "GROUP BY group_id"
    )
    suspend fun getUnreadCountRows(currentUserId: String): List<GroupUnreadCountRow>

    @Query(
        "UPDATE group_messages " +
            "SET status = 'delivered', " +
            "delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END " +
            "WHERE group_id = :groupId " +
            "AND id = :messageId " +
            "AND sender_id != :currentUserId " +
            "AND LOWER(COALESCE(status, '')) = 'sent'"
    )
    suspend fun markMessageAsDelivered(
        groupId: String,
        messageId: String,
        now: String,
        currentUserId: String
    )

    @Query("UPDATE group_messages SET deleted_for_all = 1, content = '', content_type = 'text', media_url = NULL, poll_id = NULL, reply_to_id = NULL, reply_to_json = NULL, reactions_json = '{}' WHERE id = :messageId AND group_id = :conversationId")
    suspend fun redactDeletedMessage(conversationId: String, messageId: String)

    @Query("UPDATE groups SET last_message = 'Mensaje eliminado' WHERE id = :conversationId AND (SELECT id FROM group_messages WHERE group_id = :conversationId ORDER BY created_at DESC, id DESC LIMIT 1) = :messageId AND COALESCE(julianday(last_message_at), -1) = COALESCE(julianday(COALESCE((SELECT created_at FROM group_messages WHERE id = :messageId AND group_id = :conversationId), :createdAt)), -2)")
    suspend fun redactDeletedPreview(conversationId: String, messageId: String, createdAt: String?)
}



