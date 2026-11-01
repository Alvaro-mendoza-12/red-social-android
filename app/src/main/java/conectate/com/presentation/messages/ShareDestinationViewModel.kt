package conectate.com.presentation.messages

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.MomentRepository
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.UserRepository
import conectate.com.util.IncomingShareKind
import conectate.com.util.IncomingSharePayload
import conectate.com.util.IncomingSharedItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SharePublishDestination {
    POST,
    STORY,
    AVATAR,
    COVER
}

@HiltViewModel
class ShareDestinationViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val messageRepository: MessageRepository,
    private val groupRepository: GroupRepository,
    private val communityRepository: CommunityRepository,
    private val postRepository: PostRepository,
    private val momentRepository: MomentRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val requests = ShareRequestSession(viewModelScope, authRepository::captureAccountLease) { error ->
        android.util.Log.w("ShareDestination", "Share operation failed", error)
    }
    val actionState: StateFlow<ShareActionState> = requests.state
    private val batch = ShareBatchDelivery()

    val destinations: StateFlow<List<ShareDestination>> = combine(
        messageRepository.getUserChatsFlow(),
        groupRepository.getGroupsFlow(),
        communityRepository.getCommunitiesFlow()
    ) { chats, groups, communities ->
        buildList {
            chats.filterNot { it.chat.is_group }.forEach { entry ->
                add(
                    ShareDestination(
                        id = entry.chat.id,
                        type = ShareDestinationType.CHAT,
                        title = entry.nickname
                            ?: entry.otherUserName
                            ?: entry.chat.nickname
                            ?: "Chat",
                        imageUrl = entry.otherUserImage,
                        subtitle = "Chat",
                        peerUserId = entry.otherUserId
                    )
                )
            }
            groups.forEach { entry ->
                add(
                    ShareDestination(
                        id = entry.group.id,
                        type = ShareDestinationType.GROUP,
                        title = entry.group.name,
                        imageUrl = entry.group.image,
                        subtitle = "Grupo"
                    )
                )
            }
            communities.forEach { entry ->
                add(
                    ShareDestination(
                        id = entry.community.id,
                        type = ShareDestinationType.COMMUNITY,
                        title = entry.community.name,
                        imageUrl = entry.community.image,
                        subtitle = "Comunidad"
                    )
                )
            }
        }.distinctBy(ShareDestination::key)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch { authRepository.getAuthStateFlow().collect { requests.validateSession() } }
    }

    fun refreshDestinations() {
        viewModelScope.launch { messageRepository.getUserChats() }
        viewModelScope.launch { groupRepository.syncGroups() }
        viewModelScope.launch { communityRepository.syncCommunities() }
    }

    fun bindRequest(requestId: String) {
        if (requests.bind(requestId)) {
            peopleSearch?.cancel()
            batch.reset()
            videoPeople.value = emptyList()
            refreshDestinations()
        }
    }

    fun retryPending(requestId: String) {
        val account = requests.accountForRequest(requestId) ?: return
        val frozen = batch.frozenPlan(requestId, checkNotNull(account.accountId))
        sendBatch(requestId, frozen, openWhenQueued = frozen.singleOrNull()?.content is ShareOutgoingContent.Attachment)
    }

    fun finishRequest(requestId: String) {
        if (requests.close(requestId)) {
            peopleSearch?.cancel()
            batch.reset()
            videoPeople.value = emptyList()
        }
    }

    val videoPeople = MutableStateFlow<List<ShareDestination>>(emptyList())
    private var peopleSearch: kotlinx.coroutines.Job? = null

    fun searchVideoPeople(query: String, requestId: String) {
        peopleSearch?.cancel()
        videoPeople.value = emptyList()
        val account = requests.accountForRequest(requestId) ?: return
        if (query.trim().length < 2) return
        peopleSearch = viewModelScope.launch {
            delay(180)
            val users = userRepository.searchUsers(query.trim()).getOrDefault(emptyList())
            val knownPeers = destinations.value.mapNotNull { it.peerUserId?.trim()?.lowercase() }.toSet()
            if (requests.isBound(requestId, account)) videoPeople.value = users.distinctBy { it.id }
                .filter { !it.id.equals(account.accountId, true) && it.id.trim().lowercase() !in knownPeers }.take(12).map { user ->
                    ShareDestination("person:${user.id}", ShareDestinationType.CHAT, user.displayName,
                        user.avatar_url, "@${conectate.com.data.model.profilePublicHandle(user)} · Persona", peerUserId = user.id)
                }
        }
    }

    fun shareInternalVideo(request: InternalVideoShareRequest, targets: List<ShareDestination>, caption: String) =
        shareInternalContent(request.requestId, conectate.com.data.model.videoWebLink(request.video.id), targets, caption)

    fun shareInternalPost(request: InternalPostShareRequest, targets: List<ShareDestination>, caption: String) =
        shareInternalContent(request.requestId, conectate.com.presentation.navigation.postShareLink(request.post.id), targets, caption)

    private fun shareInternalContent(requestId: String, link: String, targets: List<ShareDestination>, caption: String) {
        val content = link + caption.trim().take(800).takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
        val selected = targets.distinctBy { it.key }
        if (selected.isEmpty() || selected.size > MAX_FORWARD_DESTINATIONS) {
            requests.fail(requestId, "Selecciona entre 1 y $MAX_FORWARD_DESTINATIONS destinos")
            return
        }
        sendBatch(requestId, selected.map { ShareBatchItem("content", it, ShareOutgoingContent.Text(content)) })
    }

    fun sendIncomingShare(requestId: String, payload: IncomingSharePayload, destinationKeys: Set<String>, caption: String? = null) {
        val selected = destinations.value.filter { it.key in destinationKeys }
        val effectiveText = caption?.trim() ?: payload.text?.trim().takeUnless { it.isNullOrBlank() }
            ?: payload.subject?.trim().takeUnless { it.isNullOrBlank() }
        if (payload.items.isEmpty() && effectiveText.isNullOrBlank()) {
            requests.fail(requestId, "No se pudo leer el contenido compartido. Adjunta otra copia.")
            return
        }
        val items = selected.flatMap { destination ->
            if (payload.items.isEmpty()) listOf(ShareBatchItem("text", destination, ShareOutgoingContent.Text(effectiveText.orEmpty())))
            else payload.items.mapIndexed { index, item ->
                ShareBatchItem("item:$index", destination, ShareOutgoingContent.Attachment(item,
                    if (index == 0 && !effectiveText.isNullOrBlank()) effectiveText else defaultSharedItemContent(item)))
            }
        }
        sendBatch(requestId, items, openWhenQueued = payload.items.isNotEmpty())
    }

    fun forwardMessages(requestId: String, messages: List<ForwardMessagePayload>, destinationKeys: Set<String>) {
        val targets = destinations.value.filter { it.key in destinationKeys }
        val selected = messages.filterNot(ForwardMessagePayload::deletedForAll).distinctBy { it.id }.sortedBy { it.timestampMs }
        validateForwardSelection(selected.size, targets.size)?.let { requests.fail(requestId, it); return }
        sendBatch(requestId, targets.flatMap { target -> selected.map { message ->
            ShareBatchItem("forward:${message.id}", target, ShareOutgoingContent.Forward(message))
        } })
    }

    private fun sendBatch(requestId: String, items: List<ShareBatchItem>, openWhenQueued: Boolean = false) {
        if (items.isEmpty() || items.map { it.destination.key }.distinct().size > MAX_FORWARD_DESTINATIONS || items.size > MAX_FORWARD_OPERATIONS) {
            requests.fail(requestId, "Selecciona destinos y hasta $MAX_FORWARD_OPERATIONS envíos por lote.")
            return
        }
        requests.start(requestId, items.size) { ticket ->
            val result = batch.send(requestId, checkNotNull(ticket.account.accountId), items,
                requireCurrent = { requests.requireCurrent(ticket) },
                onProgress = { completed, total -> requests.report(ticket, ShareActionState.Running(completed, total)) },
                onQueued = { target -> if (openWhenQueued) requests.report(ticket, ShareActionState.Queued(target, requestId)) },
                onFailure = { error -> android.util.Log.w("ShareDestination", "Share item not accepted", error) },
                deliver = { item, messageId, queued -> deliver(item, messageId, ticket.account, queued) })
            if (result.accepted == result.total) ShareActionState.Completed("Compartido", false, result.lastDestination)
            else ShareActionState.Failed("Se aceptaron ${result.accepted} de ${result.total} envíos. Reintenta para enviar solo los pendientes.", retryPending = true)
        }
    }

    private suspend fun deliver(
        item: ShareBatchItem,
        messageId: String,
        account: conectate.com.data.supabase.AuthSessionLease,
        onQueued: suspend () -> Unit,
    ): Result<ShareDestination> {
        account.requireCurrent()
        val destination = if (item.destination.type == ShareDestinationType.CHAT && item.destination.id.startsWith("person:")) {
            val userId = item.destination.id.removePrefix("person:")
            val chat = messageRepository.getOrCreateChat(account, userId).getOrElse { return Result.failure(it) }
            account.requireCurrent()
            item.destination.copy(id = chat.id)
        } else item.destination
        val result = when (val content = item.content) {
            is ShareOutgoingContent.Text -> sendText(account, destination, content.content, messageId)
            is ShareOutgoingContent.Attachment -> sendMedia(account, destination, content.item, content.content, messageId, onQueued)
            is ShareOutgoingContent.Forward -> forwardOne(account, content.source, destination, messageId)
        }
        result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        account.requireCurrent()
        return result.map { destination }
    }

    private suspend fun sendText(account: conectate.com.data.supabase.AuthSessionLease, destination: ShareDestination, content: String, messageId: String): Result<Unit> =
        when (destination.type) {
            ShareDestinationType.CHAT -> messageRepository.sendMessage(accountLease = account, chatId = destination.id,
                content = content, messageId = messageId, allowRapidMediaBatch = true)
                .fold(onSuccess = ::confirmedDirectContentShare, onFailure = { Result.failure(it) })
            ShareDestinationType.GROUP -> groupRepository.sendGroupMessage(accountLease = account, groupId = destination.id,
                content = content, messageId = messageId, requireServerAcknowledgement = true)
            ShareDestinationType.COMMUNITY -> communityRepository.sendCommunityMessage(accountLease = account, communityId = destination.id,
                content = content, messageId = messageId, requireServerAcknowledgement = true)
        }

    private suspend fun sendMedia(account: conectate.com.data.supabase.AuthSessionLease, destination: ShareDestination,
        item: IncomingSharedItem, content: String, messageId: String, onQueued: suspend () -> Unit): Result<Unit> =
        when (destination.type) {
            ShareDestinationType.CHAT -> messageRepository.sendMessage(accountLease = account, chatId = destination.id,
                content = content, mediaUri = item.uri, mediaType = item.messageContentType, messageId = messageId,
                allowRapidMediaBatch = true, onQueued = onQueued).map { Unit }
            ShareDestinationType.GROUP -> groupRepository.sendGroupMediaMessage(accountLease = account, groupId = destination.id,
                sourceUri = item.uri, contentType = item.messageContentType, content = content, stableMessageId = messageId, onQueued = onQueued)
            ShareDestinationType.COMMUNITY -> communityRepository.sendCommunityMediaMessage(accountLease = account, communityId = destination.id,
                sourceUri = item.uri, contentType = item.messageContentType, content = content, stableMessageId = messageId, onQueued = onQueued)
        }

    private suspend fun forwardOne(account: conectate.com.data.supabase.AuthSessionLease, source: ForwardMessagePayload,
        destination: ShareDestination, messageId: String): Result<Unit> {
        val mediaUri = source.mediaUrl?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme.equals("file", true) || it.scheme.equals("content", true) }
        return when (destination.type) {
            ShareDestinationType.CHAT -> messageRepository.sendMessage(accountLease = account, chatId = destination.id,
                content = source.content.ifBlank { source.preview }, mediaUri = mediaUri, mediaType = source.contentType,
                existingMediaUrl = source.mediaUrl.takeUnless { mediaUri != null }, messageId = messageId,
                isForwarded = true, forwardedFromMessageId = source.id, forwardedFromUserId = source.senderId,
                allowRapidMediaBatch = true).map { Unit }
            ShareDestinationType.GROUP -> groupRepository.sendGroupMessage(accountLease = account, groupId = destination.id,
                content = source.content.ifBlank { source.preview }, contentType = source.contentType, mediaUrl = source.mediaUrl,
                isForwarded = true, forwardedFromMessageId = source.id, forwardedFromUserId = source.senderId, messageId = messageId)
            ShareDestinationType.COMMUNITY -> communityRepository.sendCommunityMessage(accountLease = account, communityId = destination.id,
                content = source.content.ifBlank { source.preview }, contentType = source.contentType, mediaUrl = source.mediaUrl,
                isForwarded = true, forwardedFromMessageId = source.id, forwardedFromUserId = source.senderId, messageId = messageId)
        }
    }

    fun publishIncomingShare(payload: IncomingSharePayload, destination: SharePublishDestination, caption: String, isPublic: Boolean, requestId: String = payload.id) {
        requests.start(requestId, 1) { ticket ->
            requests.requireCurrent(ticket)
            val result = when (destination) {
                SharePublishDestination.POST -> publishPost(payload, caption, isPublic)
                SharePublishDestination.STORY -> publishStory(payload, caption)
                SharePublishDestination.AVATAR -> updateAvatar(payload)
                SharePublishDestination.COVER -> updateCover(payload)
            }
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            requests.requireCurrent(ticket)
            if (result.isSuccess) ShareActionState.Completed("Guardado", false)
            else ShareActionState.Failed("No se pudo guardar. Vuelve a intentarlo.")
        }
    }

    private suspend fun publishPost(
        payload: IncomingSharePayload,
        caption: String,
        isPublic: Boolean
    ): Result<Unit> {
        val media = payload.items.filter(IncomingSharedItem::isPostCompatible).take(MAX_POST_MEDIA)
        val content = caption.trim().ifBlank { payload.text.orEmpty().trim() }
        if (media.isEmpty() && content.isBlank()) {
            return Result.failure(Exception("La publicación no tiene contenido compatible"))
        }
        return postRepository.createPost(
            content = content,
            mediaUris = media.map(IncomingSharedItem::uri),
            visibility = if (isPublic) "public" else "private"
        ).map { Unit }
    }

    private suspend fun publishStory(payload: IncomingSharePayload, caption: String): Result<Unit> {
        val item = payload.items.firstOrNull(IncomingSharedItem::isStoryCompatible)
            ?: return Result.failure(Exception("Selecciona una imagen WEBP, JPG, PNG o un video MP4"))
        return momentRepository.createMoment(
            mediaUri = item.uri,
            mediaType = if (item.kind == IncomingShareKind.VIDEO) "video" else "image",
            durationHours = 24,
            durationType = "24h",
            visibility = "all",
            visibilityType = "public",
            excludedIds = emptyList(),
            caption = caption.trim().ifBlank { payload.text?.trim() }
        ).map { Unit }
    }

    private suspend fun updateAvatar(payload: IncomingSharePayload): Result<Unit> {
        val item = payload.items.firstOrNull(IncomingSharedItem::isAvatarCompatible)
            ?: return Result.failure(Exception("No hay una imagen, GIF o video compatible"))
        val userId = authRepository.getCurrentUserId()
            ?: return Result.failure(Exception("Inicia sesión para actualizar tu perfil"))
        val upload = userRepository.uploadProfileAvatarMedia(item.uri).getOrElse { return Result.failure(it) }
        val update = userRepository.updateProfile(
            userId = userId,
            request = UpdateProfileRequest(
                avatar_url = upload.url,
                avatar_media_type = upload.mediaType,
                avatar_video_muted = true,
                avatar_duration_ms = upload.durationMs
            )
        )
        if (update.isFailure) userRepository.deleteUploadedProfileMedia(upload.url)
        return update
    }

    private suspend fun updateCover(payload: IncomingSharePayload): Result<Unit> {
        val item = payload.items.firstOrNull(IncomingSharedItem::isCoverCompatible)
            ?: return Result.failure(Exception("No hay una imagen compatible para la portada"))
        val userId = authRepository.getCurrentUserId()
            ?: return Result.failure(Exception("Inicia sesión para actualizar tu perfil"))
        val url = userRepository.uploadCoverImage(item.uri).getOrElse { return Result.failure(it) }
        val update = userRepository.updateProfile(
            userId = userId,
            request = UpdateProfileRequest(cover_url = url)
        )
        if (update.isFailure) userRepository.deleteUploadedProfileMedia(url)
        return update
    }
}

internal fun IncomingSharedItem.isPostCompatible(): Boolean = kind in setOf(
    IncomingShareKind.IMAGE,
    IncomingShareKind.STICKER,
    IncomingShareKind.VIDEO
)

internal fun IncomingSharedItem.isStoryCompatible(): Boolean = kind in setOf(
    IncomingShareKind.IMAGE,
    IncomingShareKind.STICKER,
    IncomingShareKind.VIDEO
)

internal fun IncomingSharedItem.isAvatarCompatible(): Boolean = kind in setOf(
    IncomingShareKind.IMAGE,
    IncomingShareKind.STICKER,
    IncomingShareKind.GIF,
    IncomingShareKind.VIDEO
)

internal fun IncomingSharedItem.isCoverCompatible(): Boolean = kind in setOf(
    IncomingShareKind.IMAGE,
    IncomingShareKind.STICKER
)

private fun defaultSharedItemContent(item: IncomingSharedItem): String = when (item.kind) {
    IncomingShareKind.IMAGE -> "Imagen"
    IncomingShareKind.GIF -> "GIF"
    IncomingShareKind.STICKER -> "Sticker"
    IncomingShareKind.VIDEO -> "Video"
    IncomingShareKind.AUDIO -> "Audio"
    IncomingShareKind.DOCUMENT -> item.displayName?.let { "Archivo: $it" } ?: "Archivo"
}

private const val MAX_POST_MEDIA = 10
