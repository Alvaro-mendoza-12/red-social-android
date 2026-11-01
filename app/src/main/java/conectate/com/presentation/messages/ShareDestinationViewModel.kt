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

sealed interface ShareActionState {
    data object Idle : ShareActionState
    data class Running(val completed: Int, val total: Int) : ShareActionState
    data class Completed(val message: String, val hadFailures: Boolean, val openDestination: ShareDestination? = null) : ShareActionState
    data class Failed(val message: String) : ShareActionState
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

    private val _actionState = MutableStateFlow<ShareActionState>(ShareActionState.Idle)
    val actionState: StateFlow<ShareActionState> = _actionState
    private var incomingSendGeneration = 0L

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
        refreshDestinations()
    }

    fun refreshDestinations() {
        viewModelScope.launch { messageRepository.getUserChats() }
        viewModelScope.launch { groupRepository.syncGroups() }
        viewModelScope.launch { communityRepository.syncCommunities() }
    }

    fun resetActionState() {
        incomingSendGeneration++
        peopleSearch?.cancel()
        contentSendJob?.cancel()
        internalContentSender.reset()
        videoPeople.value = emptyList()
        _actionState.value = ShareActionState.Idle
    }

    val videoPeople = MutableStateFlow<List<ShareDestination>>(emptyList())
    private var peopleSearch: kotlinx.coroutines.Job? = null
    private val internalContentSender = InternalContentShareSender()
    private var contentSendJob: kotlinx.coroutines.Job? = null

    fun searchVideoPeople(query: String) {
        peopleSearch?.cancel()
        videoPeople.value = emptyList()
        val account = authRepository.getCurrentUserId() ?: return
        if (query.trim().length < 2) return
        peopleSearch = viewModelScope.launch {
            delay(180)
            val users = userRepository.searchUsers(query.trim()).getOrDefault(emptyList())
            val knownPeers = destinations.value.mapNotNull { it.peerUserId?.trim()?.lowercase() }.toSet()
            if (authRepository.getCurrentUserId() == account) videoPeople.value = users.distinctBy { it.id }
                .filter { !it.id.equals(account, true) && it.id.trim().lowercase() !in knownPeers }.take(12).map { user ->
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
        if (_actionState.value is ShareActionState.Running) return
        val account = authRepository.getCurrentUserId() ?: run {
            _actionState.value = ShareActionState.Failed("Inicia sesión para compartir.")
            return
        }
        if (targets.isEmpty() || targets.size > MAX_FORWARD_DESTINATIONS) {
            _actionState.value = ShareActionState.Failed("Selecciona entre 1 y $MAX_FORWARD_DESTINATIONS destinos")
            return
        }
        val generation = ++incomingSendGeneration
        val content = link + caption.trim().take(800).takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
        _actionState.value = ShareActionState.Running(0, targets.size)
        contentSendJob = viewModelScope.launch {
            try {
                val result = internalContentSender.send(requestId, account, targets, content,
                    currentAccount = authRepository::getCurrentUserId,
                    resolvePerson = { id -> messageRepository.getOrCreateChat(id).getOrNull()?.let { chat ->
                        ShareDestination(chat.id, ShareDestinationType.CHAT, "Chat", peerUserId = id)
                    } }, send = ::sendText)
                if (generation != incomingSendGeneration || account != authRepository.getCurrentUserId()) return@launch
                _actionState.value = if (result.sent == result.total)
                    ShareActionState.Completed("Compartido en ${result.sent} destino(s)", false, result.lastDestination)
                else ShareActionState.Failed("Se compartió en ${result.sent} de ${result.total} destinos. Reintenta para enviar solo los pendientes.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == incomingSendGeneration) _actionState.value = ShareActionState.Failed(
                    if (account != authRepository.getCurrentUserId()) "La cuenta cambió. Abre Compartir de nuevo."
                    else "No se pudo completar el envío. Puedes reintentar los destinos pendientes.")
            }
        }
    }

    fun sendIncomingShare(
        payload: IncomingSharePayload,
        destinationKeys: Set<String>,
        caption: String? = null,
        onDestinationQueued: (ShareDestination) -> Unit = {},
    ) {
        if (_actionState.value is ShareActionState.Running) return
        val selected = destinations.value.filter { destinationKeys.contains(it.key) }
        if (selected.isEmpty()) {
            _actionState.value = ShareActionState.Failed("Selecciona al menos un destino")
            return
        }
        if (selected.size > MAX_FORWARD_DESTINATIONS) {
            _actionState.value = ShareActionState.Failed(
                "Puedes compartir a un máximo de $MAX_FORWARD_DESTINATIONS destinos por vez"
            )
            return
        }
        val effectiveText = caption?.trim() ?: payload.text?.trim().takeUnless { it.isNullOrBlank() }
            ?: payload.subject?.trim().takeUnless { it.isNullOrBlank() }
        if (payload.items.isEmpty() && effectiveText.isNullOrBlank()) {
            _actionState.value = ShareActionState.Failed(
                payload.captureError ?: "No se pudo leer el contenido compartido"
            )
            return
        }

        val actor = authRepository.getCurrentUserId() ?: run {
            _actionState.value = ShareActionState.Failed("Inicia sesión para compartir.")
            return
        }
        val generation = ++incomingSendGeneration
        viewModelScope.launch {
            fun report(state: ShareActionState) {
                if (generation == incomingSendGeneration) _actionState.value = state
            }
            val operationsPerTarget = payload.items.size.coerceAtLeast(1)
            val total = operationsPerTarget * selected.size
            var completed = 0
            var successes = 0
            val errors = mutableListOf<String>()
            report(ShareActionState.Running(0, total))

            selected.forEach { destination ->
                if (actor != authRepository.getCurrentUserId()) {
                    report(ShareActionState.Failed("La cuenta cambió. Abre Compartir de nuevo."))
                    return@launch
                }
                if (payload.items.isEmpty()) {
                    sendText(destination, effectiveText.orEmpty()).fold(
                        onSuccess = { successes += 1 },
                        onFailure = { errors += it.message ?: "No se pudo enviar" }
                    )
                    completed += 1
                    report(ShareActionState.Running(completed, total))
                } else {
                    payload.items.forEachIndexed { index, item ->
                        if (actor != authRepository.getCurrentUserId()) {
                            report(ShareActionState.Failed("La cuenta cambió. Abre Compartir de nuevo."))
                            return@launch
                        }
                        val content = if (index == 0 && !effectiveText.isNullOrBlank()) {
                            effectiveText
                        } else {
                            defaultSharedItemContent(item)
                        }
                        sendMedia(destination, item, content, onQueued = {
                            // The original share can be consumed only after the repository
                            // has copied it and durably queued its optimistic message.
                            if (total == 1) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                    if (generation == incomingSendGeneration && actor == authRepository.getCurrentUserId())
                                        onDestinationQueued(destination)
                                }
                            }
                        }).fold(
                            onSuccess = { successes += 1 },
                            onFailure = { errors += it.message ?: "No se pudo enviar" }
                        )
                        completed += 1
                        report(ShareActionState.Running(completed, total))
                        if (index < payload.items.lastIndex) delay(MEDIA_BATCH_INTERVAL_MS)
                    }
                }
            }

            report(when {
                successes == 0 -> ShareActionState.Failed(errors.firstOrNull() ?: "No se pudo compartir")
                errors.isNotEmpty() -> ShareActionState.Completed(
                    message = "Se enviaron $successes de $total elementos",
                    hadFailures = true
                )
                else -> ShareActionState.Completed(
                    message = "Compartido en ${selected.size} destino(s)",
                    hadFailures = false
                )
            })
        }
    }

    fun publishIncomingShare(
        payload: IncomingSharePayload,
        destination: SharePublishDestination,
        caption: String,
        isPublic: Boolean
    ) {
        if (_actionState.value is ShareActionState.Running) return
        viewModelScope.launch {
            _actionState.value = ShareActionState.Running(0, 1)
            val result = when (destination) {
                SharePublishDestination.POST -> publishPost(payload, caption, isPublic)
                SharePublishDestination.STORY -> publishStory(payload, caption)
                SharePublishDestination.AVATAR -> updateAvatar(payload)
                SharePublishDestination.COVER -> updateCover(payload)
            }
            _actionState.value = result.fold(
                onSuccess = {
                    ShareActionState.Completed(
                        message = when (destination) {
                            SharePublishDestination.POST -> "Publicación creada"
                            SharePublishDestination.STORY -> "Historia publicada"
                            SharePublishDestination.AVATAR -> "Foto de perfil actualizada"
                            SharePublishDestination.COVER -> "Portada actualizada"
                        },
                        hadFailures = false
                    )
                },
                onFailure = { ShareActionState.Failed(it.message ?: "No se pudo publicar") }
            )
        }
    }

    fun forwardMessages(
        messages: List<ForwardMessagePayload>,
        destinationKeys: Set<String>
    ) {
        if (_actionState.value is ShareActionState.Running) return
        val selectedDestinations = destinations.value.filter { destinationKeys.contains(it.key) }
        val selectedMessages = messages
            .filterNot(ForwardMessagePayload::deletedForAll)
            .distinctBy(ForwardMessagePayload::id)
            .sortedBy(ForwardMessagePayload::timestampMs)
        validateForwardSelection(selectedMessages.size, selectedDestinations.size)?.let { validationError ->
            _actionState.value = ShareActionState.Failed(validationError)
            return
        }

        viewModelScope.launch {
            val total = selectedMessages.size * selectedDestinations.size
            var completed = 0
            var successes = 0
            val errors = mutableListOf<String>()
            _actionState.value = ShareActionState.Running(0, total)
            selectedDestinations.forEach { destination ->
                selectedMessages.forEachIndexed { index, message ->
                    forwardOne(message, destination).fold(
                        onSuccess = { successes += 1 },
                        onFailure = { errors += it.message ?: "No se pudo reenviar" }
                    )
                    completed += 1
                    _actionState.value = ShareActionState.Running(completed, total)
                    if (index < selectedMessages.lastIndex) delay(MEDIA_BATCH_INTERVAL_MS)
                }
            }
            _actionState.value = when {
                successes == 0 -> ShareActionState.Failed(errors.firstOrNull() ?: "No se pudo reenviar")
                errors.isNotEmpty() -> ShareActionState.Completed(
                    message = "Se reenviaron $successes de $total mensajes",
                    hadFailures = true
                )
                else -> ShareActionState.Completed(
                    message = "Reenviado a ${selectedDestinations.size} destino(s)",
                    hadFailures = false
                )
            }
        }
    }

    private suspend fun sendText(destination: ShareDestination, content: String, messageId: String? = null): Result<Unit> {
        return when (destination.type) {
            ShareDestinationType.CHAT -> messageRepository.sendMessage(
                chatId = destination.id,
                content = content,
                messageId = messageId,
                allowRapidMediaBatch = true
            ).fold(onSuccess = ::confirmedDirectContentShare, onFailure = { Result.failure(it) })
            ShareDestinationType.GROUP -> groupRepository.sendGroupMessage(destination.id, content, messageId = messageId,
                requireServerAcknowledgement = true)
            ShareDestinationType.COMMUNITY -> communityRepository.sendCommunityMessage(destination.id, content, messageId = messageId,
                requireServerAcknowledgement = true)
        }
    }

    private suspend fun sendMedia(
        destination: ShareDestination,
        item: IncomingSharedItem,
        content: String,
        onQueued: suspend () -> Unit = {},
    ): Result<Unit> {
        return when (destination.type) {
            ShareDestinationType.CHAT -> messageRepository.sendMessage(
                chatId = destination.id,
                content = content,
                mediaUri = item.uri,
                mediaType = item.messageContentType,
                allowRapidMediaBatch = true,
                onQueued = onQueued,
            ).map { Unit }
            ShareDestinationType.GROUP -> groupRepository.sendGroupMediaMessage(
                groupId = destination.id,
                sourceUri = item.uri,
                contentType = item.messageContentType,
                content = content,
                onQueued = onQueued,
            )
            ShareDestinationType.COMMUNITY -> communityRepository.sendCommunityMediaMessage(
                communityId = destination.id,
                sourceUri = item.uri,
                contentType = item.messageContentType,
                content = content,
                onQueued = onQueued,
            )
        }
    }

    private suspend fun forwardOne(
        source: ForwardMessagePayload,
        destination: ShareDestination
    ): Result<Unit> {
        val mediaUri = source.mediaUrl
            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
        val remoteMediaUrl = source.mediaUrl.takeUnless { mediaUri != null }
        val content = source.content.ifBlank { source.preview }
        return when (destination.type) {
            ShareDestinationType.CHAT -> messageRepository.sendMessage(
                chatId = destination.id,
                content = content,
                mediaUri = mediaUri,
                mediaType = source.contentType,
                existingMediaUrl = remoteMediaUrl,
                isForwarded = true,
                forwardedFromMessageId = source.id,
                forwardedFromUserId = source.senderId,
                allowRapidMediaBatch = true
            ).map { Unit }
            ShareDestinationType.GROUP -> groupRepository.sendGroupMessage(
                groupId = destination.id,
                content = content,
                contentType = source.contentType,
                mediaUrl = source.mediaUrl,
                isForwarded = true,
                forwardedFromMessageId = source.id,
                forwardedFromUserId = source.senderId
            )
            ShareDestinationType.COMMUNITY -> communityRepository.sendCommunityMessage(
                communityId = destination.id,
                content = content,
                contentType = source.contentType,
                mediaUrl = source.mediaUrl,
                isForwarded = true,
                forwardedFromMessageId = source.id,
                forwardedFromUserId = source.senderId
            )
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
private const val MEDIA_BATCH_INTERVAL_MS = 350L
