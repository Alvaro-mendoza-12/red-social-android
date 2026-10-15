package conectate.com.data.local.dao

import androidx.room.*
import conectate.com.data.local.entity.CommunityEntity
import conectate.com.data.local.entity.CommunityMemberEntity
import conectate.com.data.local.entity.CommunityMessageEntity
import kotlinx.coroutines.flow.Flow

data class CommunityUnreadCountRow(
    val communityId: String,
    val unreadCount: Int
)

@Dao
interface CommunityDao {
    @Query("UPDATE communities SET unread_count = MAX(0, unread_count - :count) WHERE id = :communityId")
    suspend fun subtractReadMessages(communityId: String, count: Int)
    // COMMUNITIES
    @Query("SELECT * FROM communities WHERE is_deleted = 0 ORDER BY COALESCE(last_message_at, created_at) DESC")
    fun getAllCommunitiesFlow(): Flow<List<CommunityEntity>>

    @Query(
        "SELECT communities.* FROM communities " +
            "INNER JOIN community_members ON community_members.community_id = communities.id " +
            "WHERE communities.is_deleted = 0 AND community_members.user_id = :userId " +
            "ORDER BY COALESCE(communities.last_message_at, communities.created_at) DESC"
    )
    fun getCommunitiesForUserFlow(userId: String): Flow<List<CommunityEntity>>

    @Query("SELECT * FROM communities WHERE is_deleted = 0 ORDER BY COALESCE(last_message_at, created_at) DESC")
    suspend fun getAllCommunitiesSync(): List<CommunityEntity>

    @Query("SELECT * FROM communities WHERE id = :communityId AND is_deleted = 0")
    suspend fun getCommunityById(communityId: String): CommunityEntity?

    @Query("UPDATE communities SET is_member = :isMember WHERE id = :communityId")
    suspend fun updateMembership(communityId: String, isMember: Boolean)
    
    @Query("SELECT * FROM communities WHERE id = :communityId AND is_deleted = 0")
    fun getCommunityByIdFlow(communityId: String): Flow<CommunityEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommunities(communities: List<CommunityEntity>)

    @Update
    suspend fun updateCommunity(community: CommunityEntity)

    @Query("UPDATE communities SET last_message = :lastMsg, last_message_at = :lastTime WHERE id = :communityId")
    suspend fun updateMetadata(communityId: String, lastMsg: String?, lastTime: String?)

    // Concurrent sends may be acknowledged out of order. Keep the newest preview.
    @Query("""
        UPDATE communities SET last_message = :lastMsg, last_message_at = :lastTime
        WHERE id = :communityId AND (
            last_message_at IS NULL OR
            COALESCE(julianday(:lastTime), 0) >= COALESCE(julianday(last_message_at), 0)
        )
    """)
    suspend fun updateMetadataIfNewer(communityId: String, lastMsg: String?, lastTime: String?)

    @Query("UPDATE communities SET unread_count = 0 WHERE id = :communityId")
    suspend fun resetUnreadCount(communityId: String)

    @Query("UPDATE communities SET unread_count = unread_count + 1 WHERE id = :communityId")
    suspend fun incrementUnreadCount(communityId: String)

    @Query("UPDATE communities SET unread_count = unread_count + :amount WHERE id = :communityId")
    suspend fun incrementUnreadCount(communityId: String, amount: Int)

    @Query("UPDATE communities SET is_deleted = 1 WHERE id = :communityId")
    suspend fun deleteCommunity(communityId: String)

    @Query("UPDATE communities SET is_member = 0 WHERE id NOT IN (:validIds)")
    suspend fun markOthersAsNotJoined(validIds: List<String>)

    // MEMBERS
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<CommunityMemberEntity>)

    @Query("SELECT * FROM community_members WHERE community_id = :communityId AND role != 'former_member'")
    fun getMembersFlow(communityId: String): Flow<List<CommunityMemberEntity>>

    @Query("SELECT joined_at FROM community_members WHERE community_id = :roomId AND user_id = :userId")
    suspend fun getMemberJoinedAt(roomId: String, userId: String): String?

    @Query("DELETE FROM community_members WHERE community_id = :communityId AND user_id = :userId")
    suspend fun removeMember(communityId: String, userId: String)

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM community_members " +
            "INNER JOIN communities ON communities.id = community_members.community_id " +
            "WHERE community_members.community_id = :communityId " +
            "AND community_members.user_id = :userId " +
            "AND communities.is_deleted = 0 " +
            "AND community_members.role != 'former_member'" +
        ")"
    )
    fun isUserMemberFlow(communityId: String, userId: String): Flow<Boolean>

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM community_members " +
            "INNER JOIN communities ON communities.id = community_members.community_id " +
            "WHERE community_members.community_id = :communityId " +
            "AND community_members.user_id = :userId " +
            "AND communities.is_deleted = 0 " +
            "AND community_members.role != 'former_member'" +
        ")"
    )
    suspend fun isUserMemberSync(communityId: String, userId: String): Boolean

    @Query("DELETE FROM community_members WHERE user_id = :userId")
    suspend fun deleteAllMembershipsForUser(userId: String)

    @Query("DELETE FROM community_members WHERE user_id = :userId AND community_id NOT IN (:validIds)")
    suspend fun deleteUserMembershipsNotIn(userId: String, validIds: List<String>)

    @Query("SELECT role FROM community_members WHERE community_id = :communityId AND user_id = :userId AND role != 'former_member'")
    fun getUserRoleFlow(communityId: String, userId: String): Flow<String?>

    @Query("SELECT role FROM community_members WHERE community_id = :communityId AND user_id = :userId AND role != 'former_member'")
    suspend fun getUserRoleSync(communityId: String, userId: String): String?

    // MESSAGES
    @Query("SELECT * FROM community_messages WHERE community_id = :communityId ORDER BY created_at ASC")
    fun getMessagesFlow(communityId: String): Flow<List<CommunityMessageEntity>>

    @Query("SELECT * FROM community_messages WHERE community_id = :communityId ORDER BY created_at ASC")
    suspend fun getMessagesSync(communityId: String): List<CommunityMessageEntity>

    @Query("DELETE FROM community_messages WHERE community_id = :roomId AND id IN (:ids) AND is_synced = 1 AND pending_action IS NULL")
    suspend fun deleteInaccessibleHistory(roomId: String, ids: List<String>)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<CommunityMessageEntity>)

    @Query("SELECT id FROM community_messages WHERE community_id = :communityId")
    suspend fun getCommunityMessageIds(communityId: String): List<String>

    @Query("SELECT * FROM community_messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): CommunityMessageEntity?

    @Query("SELECT * FROM community_messages WHERE is_synced = 0 AND pending_action = 'send' ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingOutgoingMessages(limit: Int): List<CommunityMessageEntity>

    @Query("UPDATE community_messages SET is_synced = :isSynced, pending_action = :pendingAction WHERE id = :messageId")
    suspend fun updateMessageSyncStatus(messageId: String, isSynced: Boolean, pendingAction: String?)

    @Query("UPDATE community_messages SET retry_count = retry_count + 1, last_retry_at = :lastRetryAt WHERE id = :messageId")
    suspend fun markRetryAttempt(messageId: String, lastRetryAt: String)

    @Query("UPDATE community_messages SET status = 'read', read_at = :now, delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END WHERE community_id = :communityId AND sender_id != :currentUserId AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received')")
    suspend fun markAllAsRead(communityId: String, now: String, currentUserId: String)

    @Query("UPDATE community_messages SET status = 'delivered', delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END WHERE community_id = :communityId AND sender_id != :currentUserId AND LOWER(COALESCE(status, '')) = 'sent'")
    suspend fun markAllAsDelivered(communityId: String, now: String, currentUserId: String)

    @Query(
        "UPDATE community_messages " +
            "SET status = 'read', " +
            "read_at = :now, " +
            "delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END " +
            "WHERE community_id = :communityId " +
            "AND sender_id != :currentUserId " +
            "AND id IN (:messageIds) " +
            "AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received')"
    )
    suspend fun markMessagesAsReadByIds(
        communityId: String,
        now: String,
        currentUserId: String,
        messageIds: List<String>
    )

    @Query(
            "SELECT COUNT(*) FROM community_messages " +
            "WHERE community_id = :communityId " +
            "AND sender_id != :currentUserId " +
            "AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received')"
    )
    suspend fun countUnreadIncoming(communityId: String, currentUserId: String): Int

    @Query(
        "SELECT community_id AS communityId, COUNT(*) AS unreadCount " +
            "FROM community_messages " +
            "WHERE sender_id != :currentUserId " +
            "AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received') " +
            "GROUP BY community_id"
    )
    suspend fun getUnreadCountRows(currentUserId: String): List<CommunityUnreadCountRow>

    @Query(
        "UPDATE community_messages " +
            "SET status = 'delivered', " +
            "delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END " +
            "WHERE community_id = :communityId " +
            "AND id = :messageId " +
            "AND sender_id != :currentUserId " +
            "AND LOWER(COALESCE(status, '')) = 'sent'"
    )
    suspend fun markMessageAsDelivered(
        communityId: String,
        messageId: String,
        now: String,
        currentUserId: String
    )

    @Query("UPDATE community_messages SET deleted_for_all = 1, content = '', content_type = 'text', media_url = NULL, poll_id = NULL, reply_to_id = NULL, reply_to_json = NULL, reactions_json = '{}' WHERE id = :messageId AND community_id = :conversationId")
    suspend fun redactDeletedMessage(conversationId: String, messageId: String)

    @Query("UPDATE communities SET last_message = 'Mensaje eliminado' WHERE id = :conversationId AND (SELECT id FROM community_messages WHERE community_id = :conversationId ORDER BY created_at DESC, id DESC LIMIT 1) = :messageId AND COALESCE(julianday(last_message_at), -1) = COALESCE(julianday(COALESCE((SELECT created_at FROM community_messages WHERE id = :messageId AND community_id = :conversationId), :createdAt)), -2)")
    suspend fun redactDeletedPreview(conversationId: String, messageId: String, createdAt: String?)
}
