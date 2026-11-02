package conectate.com.presentation.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.NotificationDigestOverview
import conectate.com.data.localfallback.NotificationDigestPreferences
import conectate.com.data.model.Notification
import conectate.com.data.repository.NotificationRepository
import conectate.com.data.supabase.AuthService
import conectate.com.util.LocalNotificationHelper
import conectate.com.util.notificationDisplayType as resolveNotificationType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import javax.inject.Inject

sealed class NotificationsUiState {
    object Loading : NotificationsUiState()
    data class Success(val notifications: List<Notification>) : NotificationsUiState()
    data class Error(val message: String) : NotificationsUiState()
}

sealed class NotificationDigestUiState {
    object Loading : NotificationDigestUiState()
    data class Ready(val overview: NotificationDigestOverview) : NotificationDigestUiState()
    data class Error(val message: String) : NotificationDigestUiState()
}

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val authService: AuthService,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val notificationHelper = LocalNotificationHelper(context)

    private val _notificationsState = MutableStateFlow<NotificationsUiState>(NotificationsUiState.Loading)
    val notificationsState: StateFlow<NotificationsUiState> = _notificationsState.asStateFlow()

    val unreadCount: StateFlow<Int> = notificationRepository.unreadCount

    private val _momentNotificationsEnabled = MutableStateFlow<Boolean?>(null)
    val momentNotificationsEnabled: StateFlow<Boolean?> = _momentNotificationsEnabled.asStateFlow()
    private val _momentNotificationsSaving = MutableStateFlow(false)
    val momentNotificationsSaving: StateFlow<Boolean> = _momentNotificationsSaving.asStateFlow()
    private val _momentPreferencesLoading = MutableStateFlow(false)
    val momentPreferencesLoading: StateFlow<Boolean> = _momentPreferencesLoading.asStateFlow()
    private val _momentPreferencesError = MutableStateFlow<String?>(null)
    val momentPreferencesError: StateFlow<String?> = _momentPreferencesError.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _digestState = MutableStateFlow<NotificationDigestUiState>(
        NotificationDigestUiState.Loading
    )
    val digestState: StateFlow<NotificationDigestUiState> = _digestState.asStateFlow()

    private val _digestSaving = MutableStateFlow(false)
    val digestSaving: StateFlow<Boolean> = _digestSaving.asStateFlow()

    private val _digestMessage = MutableStateFlow<String?>(null)
    val digestMessage: StateFlow<String?> = _digestMessage.asStateFlow()

    private val requests = NotificationRequests(viewModelScope, ::captureUiLease)
    private var ownerLease = authService.captureAccountLease()
    private val _ownerAccountId = MutableStateFlow(ownerLease.accountId)
    val ownerAccountId: StateFlow<String?> = _ownerAccountId.asStateFlow()
    private val _ownerRevision = MutableStateFlow(0L)
    internal val ownerRevision: StateFlow<Long> = _ownerRevision.asStateFlow()
    private var notificationJob: Job? = null
    private var listRequested = false
    private var digestRequested = false
    private var momentPreferencesRequested = false
    private var digestRefreshRequested = false
    private val refresh = NotificationRefresh(viewModelScope, {
        if (listRequested) loadNotificationsRequest()?.join()
        else updateUnreadCountRequest()?.join()
        if (digestRefreshRequested && digestRequested && !_digestSaving.value) {
            digestRefreshRequested = false
            loadNotificationDigestRequest()?.join()
        }
    }, { android.util.Log.w("NotificationViewModel", "Notification refresh interrupted", it) })

    init {
        viewModelScope.launch {
            authService.currentSession.collect {
                val lease = authService.captureAccountLease()
                val changed = !ownerLease.isCurrent() || ownerLease.accountId != lease.accountId
                ownerLease = lease
                if (changed) {
                    requests.cancelAll()
                    refresh.cancel()
                    digestRefreshRequested = false
                    _ownerAccountId.value = lease.accountId
                    _ownerRevision.value += 1
                    _notificationsState.value = if (lease.isCurrent()) NotificationsUiState.Loading
                        else NotificationsUiState.Error("Inicia sesión para ver tus notificaciones.")
                    _digestState.value = if (lease.isCurrent()) NotificationDigestUiState.Loading
                        else NotificationDigestUiState.Error("Inicia sesión para ver tus preferencias.")
                    _momentNotificationsEnabled.value = null
                    _momentPreferencesLoading.value = false
                    _momentPreferencesError.value = null
                    _digestMessage.value = null
                }
                observeForAccount(lease)
                if (lease.isCurrent()) {
                    updateUnreadCount()
                    if (changed && listRequested) loadNotifications()
                    if (changed && digestRequested) loadNotificationDigest()
                    if (changed && momentPreferencesRequested) loadMomentNotificationPreferences()
                }
            }
        }
    }

    // Do not replace this lease just because the same account ID signed in again.
    private fun captureUiLease(): AuthSessionLease = ownerLease

    internal fun ownerAtRender(): () -> Boolean {
        val lease = captureUiLease()
        return lease::isCurrent
    }

    private fun observeForAccount(lease: AuthSessionLease) {
        notificationJob?.cancel()
        if (!lease.isCurrent()) return
        notificationJob = viewModelScope.launch {
            var retryMs = 1_000L
            while (isActive && lease.isCurrent()) {
                try {
                    notificationRepository.observeNotifications(lease).collect { notification ->
                        if (!lease.isCurrent() || !notification.recipientId.equals(lease.accountId, true)) return@collect

                        if (isChatCenterNotification(notification)) {
                            refresh.request()
                            return@collect
                        }
                        // When a new notification arrives:
                        // 1. Show a local system alert (Foreground feedback)
                        val digestDeferred = (
                            (notification.extraData?.get("digest_deferred") as? JsonPrimitive)
                                ?.let {
                                    it.booleanOrNull ?: it.contentOrNull?.equals("true", ignoreCase = true)
                                }
                            ) == true
                        try {
                            if (!digestDeferred) {
                                val effectiveType = resolveNotificationType(notification)
                                val typeLabel = when(effectiveType) {
                                    "follow" -> "Nuevo seguidor"
                                    "friend_request" -> "Solicitud de amistad"
                                    "like", "like_post" -> "Le gusta tu publicación"
                                    "comment", "comment_post" -> "Comentó tu publicación"
                                    "reply_post" -> "Respondió a tu comentario"
                                    "mention_post" -> conectate.com.util.postMentionAction(notification)
                                    "mention" -> "Te mencionó en un comentario"
                                    "voice_invite" -> "Te invitó a una sala de voz"
                                    "live_started", "new_live" -> "Inició un directo"
                                    "challenge_invite" -> "Te desafió a un reto"
                                    "like_video" -> "Le gusta tu video"
                                    "like_moment" -> "Le gusta tu momento"
                                    "comment_video" -> "Comentó tu video"
                                    "reply_video" -> "Respondió a tu comentario en video"
                                    "mention_video" -> "Te mencionó en un comentario de video"
                                    "mention_video_description" -> "Te mencionó en la descripción de un video"
                                    "share_video" -> "Compartió tu video"
                                    "admin_warning_video" -> "Tu video recibió una advertencia"
                                    "account_suspended" -> "Tu cuenta fue suspendida"
                                    "account_enabled" -> "Tu cuenta fue habilitada"
                                    "notification_digest" -> "Tu resumen de Novvin"
                                    else -> "Nueva notificación"
                                }

                                val name = notification.actorName ?: "Alguien"
                                val contentText = if (notification.content.isNullOrBlank()) typeLabel else notification.content

                                notificationHelper.showSocialNotification(
                                    account = conectate.com.util.NotificationAccountTarget(userId = lease.accountId),
                                    title = name,
                                    body = contentText,
                                    type = effectiveType,
                                    targetId = notification.targetId ?: "",
                                    senderId = notification.actorId,
                                    senderAvatar = notification.actorAvatarUrl,
                                    targetType = notification.targetType,
                                    extraData = notification.extraData,
                                    notificationId = notification.id
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("NotificationViewModel", "Error showing local notification", e)
                        }

                        // 2. Update the unread count badge
                        refresh.request()

                        // 3. Optionally reload the list if we are in the Success state
                        // or just to keep it fresh in memory if needed
                        val currentState = _notificationsState.value
                        if (currentState is NotificationsUiState.Success) {
                            refresh.request()
                        }
                        if (digestDeferred || resolveNotificationType(notification) == "notification_digest") {
                            digestRefreshRequested = true
                            refresh.request()
                        }

                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("NotificationViewModel", "Notification subscription interrupted", error)
                }
                if (lease.isCurrent()) delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(15_000L)
            }
        }
    }

    fun loadNotifications() {
        listRequested = true
        loadNotificationsRequest()
    }

    private fun loadNotificationsRequest(): Job? = requests.run("list",
        started = {
            _isRefreshing.value = true
            if (_notificationsState.value !is NotificationsUiState.Success) _notificationsState.value = NotificationsUiState.Loading
        },
        action = notificationRepository::getNotifications,
        success = { list -> _notificationsState.value = NotificationsUiState.Success(list.filterNot(::isChatCenterNotification)) },
        failure = { error ->
            val message = notificationError(error, "No se pudieron cargar las notificaciones. Reintenta.")
            if (_notificationsState.value is NotificationsUiState.Success) feedback(message)
            else _notificationsState.value = NotificationsUiState.Error(message)
        },
        cancelled = {
            if (_notificationsState.value is NotificationsUiState.Loading)
                _notificationsState.value = NotificationsUiState.Error("La carga se interrumpió. Inténtalo de nuevo.")
        },
        finished = { _isRefreshing.value = false },
    )

    private fun isChatCenterNotification(notification: Notification): Boolean {
        val type = resolveNotificationType(notification)
        val targetType = notification.targetType?.trim()?.lowercase().orEmpty()
        val content = notification.content?.trim()?.lowercase().orEmpty()
        if (type in setOf(
            "message",
            "new_message",
            "direct_message",
            "group_message",
            "community_message",
            "group_member_added",
            "community_member_added",
            "call",
            "incoming_call",
            "voice_call",
            "video_call",
            "call_invite",
            "call_cancel",
            "call_response",
            "call_missed",
            "missed_call",
            "voice_invite",
            "voice_return"
        )) return true
        if (type.startsWith("call_") || type.endsWith("_message")) return true
        if (targetType in setOf(
                "chat",
                "direct_message",
                "group_chat",
                "community_chat",
                "call",
                "voice_call",
                "video_call"
            )
        ) return true
        return content.contains("llamada entrante") ||
            content.contains("llamada aceptada") ||
            content.contains("llamada cancelada") ||
            content.contains("llamada perdida") ||
            content.contains("te está llamando")
    }

    fun loadNotificationDigest() {
        digestRequested = true
        loadNotificationDigestRequest()
    }

    private fun loadNotificationDigestRequest(): Job? = requests.run("digest",
        started = { if (_digestState.value !is NotificationDigestUiState.Ready) _digestState.value = NotificationDigestUiState.Loading },
        action = notificationRepository::getNotificationDigest,
        success = { _digestState.value = NotificationDigestUiState.Ready(it) },
        failure = { error ->
            val message = notificationError(error, "No se pudieron cargar tus preferencias. Reintenta.")
            if (_digestState.value is NotificationDigestUiState.Ready) _digestMessage.value = message
            else _digestState.value = NotificationDigestUiState.Error(message)
        },
        cancelled = {
            if (_digestState.value is NotificationDigestUiState.Loading)
                _digestState.value = NotificationDigestUiState.Error("La carga se interrumpió. Inténtalo de nuevo.")
        },
    )

    fun loadMomentNotificationPreferences() {
        momentPreferencesRequested = true
        requests.run("moment-preferences",
            started = { _momentPreferencesLoading.value = true; _momentPreferencesError.value = null },
            action = notificationRepository::getMomentNotificationPreferences,
            success = { _momentNotificationsEnabled.value = it.enabled },
            failure = { _momentPreferencesError.value = notificationError(it, "No se pudieron cargar los avisos de momentos.") },
            cancelled = { _momentPreferencesError.value = "La carga se interrumpió. Inténtalo de nuevo." },
            finished = { _momentPreferencesLoading.value = false },
        )
    }

    fun setMomentNotificationsEnabled(enabled: Boolean) {
        requests.cancel("moment-preferences")
        requests.run("moment-save", replace = false,
            started = { _momentNotificationsSaving.value = true },
            action = { lease -> notificationRepository.setMomentNotificationPreferences(lease, enabled) },
            success = {
                _momentNotificationsEnabled.value = it.enabled
                _digestMessage.value = if (it.enabled) "Avisos de momentos activados para las personas que sigues."
                    else "Avisos de momentos desactivados."
            },
            failure = { _digestMessage.value = notificationError(it, "No se pudo guardar el cambio. Inténtalo otra vez.") },
            finished = { _momentNotificationsSaving.value = false },
        )
    }

    fun saveNotificationDigestPreferences(preferences: NotificationDigestPreferences) {
        requests.run("digest-save", replace = false,
            started = { _digestSaving.value = true; _digestMessage.value = null; requests.cancel("digest") },
            action = { lease -> notificationRepository.updateNotificationDigestPreferences(lease, preferences) },
            success = { confirmed ->
                val previous = (_digestState.value as? NotificationDigestUiState.Ready)?.overview
                _digestState.value = NotificationDigestUiState.Ready(
                    if (previous != null) previous.copy(preferences = confirmed)
                    else NotificationDigestOverview(preferences = confirmed))
                _digestMessage.value = "Preferencias guardadas."
            },
            failure = { _digestMessage.value = notificationError(it, "No se pudieron guardar tus preferencias. Reintenta.") },
            finished = {
                _digestSaving.value = false
                if (digestRefreshRequested) refresh.request()
            },
        )
    }

    fun clearDigestMessage() { _digestMessage.value = null }

    private fun notificationError(error: Throwable, fallback: String): String = when {
        error.message.orEmpty().contains("DIGEST_CATEGORY_REQUIRED", true) -> "Elige al menos una categoría para el resumen."
        error.message.orEmpty().contains("No token", true) -> "Tu sesión venció. Inicia sesión de nuevo."
        error is java.io.IOException -> "No se pudo conectar. Revisa tu conexión e inténtalo otra vez."
        error is java.util.concurrent.TimeoutException -> "La carga está tardando demasiado. Inténtalo de nuevo."
        else -> fallback
    }

    private fun feedback(message: String) = conectate.com.presentation.common.AppFeedbackBus.showError(message)

    fun updateUnreadCount() { updateUnreadCountRequest() }
    private fun updateUnreadCountRequest(): Job? = requests.run("count", replace = false,
        action = notificationRepository::updateUnreadCount, success = {}, failure = {})

    fun markAsRead(notificationId: String) {
        val accountId = ownerLease.accountId
        requests.run("read:$notificationId", replace = false,
            action = { lease -> notificationRepository.markAsRead(lease, notificationId) },
            success = {
                requests.cancel("list")
                notificationHelper.clearSocialNotification(accountId, notificationId)
                val current = _notificationsState.value as? NotificationsUiState.Success
                if (current != null) _notificationsState.value = NotificationsUiState.Success(
                    current.notifications.map { if (it.id == notificationId) it.copy(isRead = true) else it })
                updateUnreadCount()
            },
            failure = { feedback(notificationError(it, "No se pudo marcar como leída. Inténtalo de nuevo.")) },
        )
    }

    fun markAllAsRead() {
        val accountId = ownerLease.accountId
        requests.run("read-all", replace = false,
            action = notificationRepository::markAllAsRead,
            success = {
                notificationHelper.clearAllSocialNotifications(accountId)
                loadNotifications()
            },
            failure = { feedback(notificationError(it, "No se pudieron marcar las notificaciones. Reintenta.")) },
        )
    }

    fun deleteNotification(notificationId: String) {
        val accountId = ownerLease.accountId
        requests.run("delete:$notificationId", replace = false,
            action = { lease -> notificationRepository.deleteNotification(lease, notificationId) },
            success = {
                requests.cancel("list")
                notificationHelper.clearSocialNotification(accountId, notificationId)
                val current = _notificationsState.value as? NotificationsUiState.Success
                if (current != null) _notificationsState.value = NotificationsUiState.Success(current.notifications.filterNot { it.id == notificationId })
                updateUnreadCount()
            },
            failure = { feedback(notificationError(it, "No se pudo borrar la notificación. Inténtalo de nuevo.")) },
        )
    }

    fun deleteAllNotifications() {
        val accountId = ownerLease.accountId
        requests.run("delete-all", replace = false,
            action = notificationRepository::deleteAllNotifications,
            success = {
                requests.cancel("list")
                notificationHelper.clearAllSocialNotifications(accountId)
                _notificationsState.value = NotificationsUiState.Success(emptyList())
                updateUnreadCount()
            },
            failure = { feedback(notificationError(it, "No se pudieron borrar las notificaciones. Reintenta.")) },
        )
    }
}
