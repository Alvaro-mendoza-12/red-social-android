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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
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

    init {
        // Only fetch count by default to avoid heavy list loading when just showing a badge
        updateUnreadCount()
        
        // Subscribe to Realtime notifications
        viewModelScope.launch {
            notificationRepository.observeNotifications().collect { notification ->
                if (isChatCenterNotification(notification)) {
                    updateUnreadCount()
                    return@collect
                }
                // When a new notification arrives:
                // 1. Show a local system alert (Foreground feedback)
                val digestDeferred = (
                    notification.extraData
                        ?.get("digest_deferred")
                        ?.jsonPrimitive
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
                updateUnreadCount()
                
                // 3. Optionally reload the list if we are in the Success state 
                // or just to keep it fresh in memory if needed
                val currentState = _notificationsState.value
                if (currentState is NotificationsUiState.Success) {
                    loadNotifications()
                }
                if (digestDeferred || resolveNotificationType(notification) == "notification_digest") {
                    loadNotificationDigest()
                }
            }
        }
    }

    private fun resolveNotificationType(notification: Notification): String {
        val subtype = notification.extraData
            ?.get("notification_type")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.lowercase()
        if (!subtype.isNullOrBlank()) return subtype
        val baseType = notification.type.trim().lowercase()
        if (baseType != "message") return baseType
        val lowerContent = notification.content?.trim()?.lowercase().orEmpty()
        return when {
            lowerContent.contains("cuenta fue suspendida") ||
                lowerContent.contains("cuenta ha sido suspendida") -> "account_suspended"
            lowerContent.contains("cuenta ha sido habilitada") ||
                lowerContent.contains("cuenta fue habilitada") ||
                lowerContent.contains("cuenta habilitada nuevamente") -> "account_enabled"
            else -> baseType
        }
    }

    fun loadNotifications() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _notificationsState.value = NotificationsUiState.Loading
            notificationRepository.getNotifications()
                .onSuccess { list ->
                    _notificationsState.value = NotificationsUiState.Success(
                        list.filterNot(::isChatCenterNotification)
                    )
                }
                .onFailure { e ->
                    _notificationsState.value = NotificationsUiState.Error(e.message ?: "Error desconocido")
                }
            _isRefreshing.value = false
        }
    }

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
        viewModelScope.launch {
            _digestState.value = NotificationDigestUiState.Loading
            notificationRepository.getNotificationDigest()
                .onSuccess { overview ->
                    _digestState.value = NotificationDigestUiState.Ready(overview)
                }
                .onFailure { error ->
                    _digestState.value = NotificationDigestUiState.Error(
                        digestErrorMessage(error)
                    )
                }
        }
    }

    fun loadMomentNotificationPreferences() {
        viewModelScope.launch {
            notificationRepository.getMomentNotificationPreferences()
                .onSuccess { _momentNotificationsEnabled.value = it.enabled }
                .onFailure { _digestMessage.value = digestErrorMessage(it) }
        }
    }

    fun setMomentNotificationsEnabled(enabled: Boolean) {
        if (_momentNotificationsSaving.value) return
        viewModelScope.launch {
            _momentNotificationsSaving.value = true
            notificationRepository.setMomentNotificationPreferences(enabled)
                .onSuccess {
                    _momentNotificationsEnabled.value = it.enabled
                    _digestMessage.value = if (it.enabled)
                        "Avisos de momentos activados para las personas que sigues."
                    else "Avisos de momentos desactivados."
                }
                .onFailure { _digestMessage.value = digestErrorMessage(it) }
            _momentNotificationsSaving.value = false
        }
    }

    fun saveNotificationDigestPreferences(preferences: NotificationDigestPreferences) {
        viewModelScope.launch {
            _digestSaving.value = true
            _digestMessage.value = null
            notificationRepository.updateNotificationDigestPreferences(preferences)
                .onSuccess {
                    _digestMessage.value = "Preferencias guardadas."
                    loadNotificationDigest()
                }
                .onFailure { error ->
                    _digestMessage.value = digestErrorMessage(error)
                }
            _digestSaving.value = false
        }
    }

    fun clearDigestMessage() {
        _digestMessage.value = null
    }

    private fun digestErrorMessage(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("DIGEST_CATEGORY_REQUIRED", ignoreCase = true) ->
                "Elige al menos una categoría para el resumen."
            raw.contains("network", ignoreCase = true) ||
                raw.contains("host", ignoreCase = true) ||
                raw.contains("connect", ignoreCase = true) ->
                "No se pudo conectar. Revisa tu conexión e inténtalo otra vez."
            raw.isBlank() -> "No se pudo cargar la configuración del resumen."
            else -> raw
        }
    }

    fun updateUnreadCount() {
        viewModelScope.launch {
            notificationRepository.updateUnreadCount()
        }
    }

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            if (notificationRepository.markAsRead(notificationId).isFailure) return@launch
            notificationHelper.clearSocialNotification(notificationId)
            updateUnreadCount()
            // Optionally update the local list state without full reload
            val currentState = _notificationsState.value
            if (currentState is NotificationsUiState.Success) {
                val updatedList = currentState.notifications.map {
                    if (it.id == notificationId) it.copy(isRead = true) else it
                }
                _notificationsState.value = NotificationsUiState.Success(updatedList)
            }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            if (notificationRepository.markAllAsRead().isFailure) return@launch
            notificationHelper.clearAllSocialNotifications()
            updateUnreadCount()
            loadNotifications()
        }
    }

    fun deleteNotification(notificationId: String) {
        viewModelScope.launch {
            val result = notificationRepository.deleteNotification(notificationId)
            if (result.isSuccess) {
                notificationHelper.clearSocialNotification(notificationId)
                val currentState = _notificationsState.value
                if (currentState is NotificationsUiState.Success) {
                    val updatedList = currentState.notifications.filter { it.id != notificationId }
                    _notificationsState.value = NotificationsUiState.Success(updatedList)
                }
                updateUnreadCount()
            }
        }
    }

    fun deleteAllNotifications() {
        viewModelScope.launch {
            val result = notificationRepository.deleteAllNotifications()
            if (result.isSuccess) {
                notificationHelper.clearAllSocialNotifications()
                _notificationsState.value = NotificationsUiState.Success(emptyList())
                updateUnreadCount()
            }
        }
    }
}
