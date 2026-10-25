package conectate.com.data.repository.message

import android.content.Context
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.core.media.ProfileIdentityImageStore
import conectate.com.data.local.dao.UserDao
import conectate.com.data.local.entity.UserEntity
import conectate.com.data.model.ChatWithLastMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val GENERIC_USER_NAME = "Usuario"

/**
 * Keeps the small identity snapshot needed by the inbox independent from the
 * remote refresh. A partial response must never erase a name or avatar that
 * was already available offline.
 */
@Singleton
class InboxIdentityCache @Inject constructor(
    private val userDao: UserDao,
    @param:ApplicationContext private val context: Context,
) {
    suspend fun persist(conversations: List<ChatWithLastMessage>, accountId: String, identityGeneration: Long) {
        val incomingIdentities = conversations
            .asSequence()
            .filterNot { it.chat.is_group }
            .mapNotNull(::identityFromConversation)
            .distinctBy { it.id }
            .toList()
        persistInboxIdentityBatch(userDao, incomingIdentities)
        warmAvatars(conversations.mapNotNull(::inboxAvatarUrl), accountId, identityGeneration)
    }

    private fun warmAvatars(urls: Iterable<String>, accountId: String, identityGeneration: Long) {
        ProfileIdentityImageStore.warm(context, selectInboxAvatarsForPrefetch(urls), accountId, identityGeneration)
    }
}

/** Refreshing 200 contacts should not issue 200 serialized Room lookups. */
internal suspend fun persistInboxIdentityBatch(userDao: UserDao, incoming: List<UserEntity>) {
    if (incoming.isEmpty()) return
    val existing = incoming.map(UserEntity::id).distinct().chunked(800)
        .flatMap { userDao.getUsers(it) }.associateBy(UserEntity::id)
    userDao.insertUsers(incoming.map { mergeInboxIdentity(existing[it.id], it) })
}

internal fun mergeInboxConversationIdentity(
    existing: ChatWithLastMessage?,
    incoming: ChatWithLastMessage,
): ChatWithLastMessage {
    if (existing == null) return incoming
    val incomingName = incoming.otherUserName.validInboxName()
    val existingName = existing.otherUserName.validInboxName()
    val incomingImage = inboxAvatarUrl(incoming)
    val existingImage = inboxAvatarUrl(existing)
    val finalName = incomingName ?: existingName
    val finalImage = incomingImage ?: existingImage
    return incoming.copy(
        otherUserName = finalName ?: incoming.otherUserName,
        otherUserImage = finalImage,
        chat = incoming.chat.copy(
            group_name = if (incoming.chat.is_group) {
                incoming.chat.group_name.validInboxName()
                    ?: existing.chat.group_name.validInboxName()
                    ?: incoming.chat.group_name
            } else {
                incoming.chat.group_name.validInboxName()
                    ?: existing.chat.group_name.validInboxName()
                    ?: finalName
            },
            group_image = if (incoming.chat.is_group) finalImage else incoming.chat.group_image,
        ),
    )
}

internal fun inboxAvatarUrl(conversation: ChatWithLastMessage): String? {
    val value = if (conversation.chat.is_group) {
        conversation.chat.group_image ?: conversation.otherUserImage
    } else {
        conversation.otherUserImage
    }
    return value?.trim()?.takeIf(String::isNotBlank)
}

internal fun identityFromConversation(conversation: ChatWithLastMessage): UserEntity? {
    val id = conversation.otherUserId
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotBlank)
        ?: return null
    val name = conversation.otherUserName
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(GENERIC_USER_NAME, ignoreCase = true) }
        .orEmpty()
    return UserEntity(
        id = id,
        username = name,
        full_name = name,
        avatar_url = conversation.otherUserImage?.trim()?.takeIf(String::isNotBlank),
        bio = null,
        is_verified = conversation.otherUserVerified,
        is_admin = conversation.otherUserIsAdmin,
    )
}

internal fun mergeInboxIdentity(existing: UserEntity?, incoming: UserEntity): UserEntity {
    if (existing == null) return incoming
    val incomingName = incoming.displayName
        .trim()
        .takeIf { it.isNotBlank() && !it.equals(GENERIC_USER_NAME, ignoreCase = true) }
    val incomingAvatar = incoming.avatar_url?.trim()?.takeIf(String::isNotBlank)
    return existing.copy(
        username = incomingName ?: existing.username,
        full_name = incomingName ?: existing.full_name,
        avatar_url = incomingAvatar ?: existing.avatar_url,
        is_verified = incoming.is_verified || existing.is_verified,
        is_admin = incoming.is_admin || existing.is_admin,
        updated_at = System.currentTimeMillis(),
    )
}

internal fun selectInboxAvatarsForPrefetch(
    urls: Iterable<String>,
    maxItems: Int = Int.MAX_VALUE,
): List<String> {
    if (maxItems <= 0) return emptyList()
    return urls.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        // Video identities keep using Media3's bounded cache; Coil must not
        // download a complete avatar video just to obtain one poster frame.
        .filterNot(::looksLikeVideoUrl)
        .distinctBy { MediaImageRequestFactory.avatarCacheKey(it) ?: it }
        .take(maxItems)
        .toList()
}

private fun looksLikeVideoUrl(value: String): Boolean {
    val path = value.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".webm")
}

private fun String?.validInboxName(): String? = this
    ?.trim()
    ?.takeIf {
        it.isNotBlank() &&
            !it.equals(GENERIC_USER_NAME, ignoreCase = true) &&
            !it.equals("Cargando...", ignoreCase = true) &&
            !it.equals("Chat del Sistema", ignoreCase = true)
    }
