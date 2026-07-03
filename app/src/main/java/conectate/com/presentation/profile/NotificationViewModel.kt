package conectate.com.presentation.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

sealed class NotificationsUiState {
    object Loading : NotificationsUiState()
    data class Success(val notifications: List<Notification>) : NotificationsUiState()
    data class Error(val message: String) : NotificationsUiState()
}

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val authService: AuthService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val notificationHelper = LocalNotificationHelper(context)

    private val _notificationsState = MutableStateFlow<NotificationsUiState>(NotificationsUiState.Loading)
    val notificationsState: StateFlow<NotificationsUiState> = _notificationsState.asStateFlow()

    val unreadCount: StateFlow<Int> = notificationRepository.unreadCount

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        // Only fetch count by default to avoid heavy list loading when just showing a badge
        updateUnreadCount()
        
        // Subscribe to Realtime notifications
        viewModelScope.launch {
            notificationRepository.observeNotifications().collect { notification ->
                // When a new notification arrives:
                // 1. Show a local system alert (Foreground feedback)
                try {
                    val effectiveType = resolveNotificationType(notification)
                    val typeLabel = when(effectiveType) {
                        "follow" -> "Nuevo seguidor"
                        "friend_request" -> "Solicitud de amistad"
                        "like", "like_post" -> "Le gusta tu publicación"
                        "comment", "comment_post" -> "Comentó tu publicación"
                        "reply_post" -> "Respondió a tu comentario"
                        "mention_post", "mention" -> "Te mencionó en un comentario"
                        "voice_invite" -> "Te invitó a una sala de voz"
                        "challenge_invite" -> "Te desafió a un reto"
                        "like_video" -> "Le gusta tu video"
                        "comment_video" -> "Comentó tu video"
                        "reply_video" -> "Respondió a tu comentario en video"
                        "mention_video" -> "Te mencionó en un comentario de video"
                        "share_video" -> "Compartió tu video"
                        "admin_warning_video" -> "Tu video recibió una advertencia"
                        "account_suspended" -> "Tu cuenta fue suspendida"
                        "account_enabled" -> "Tu cuenta fue habilitada"
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
                        extraData = notification.extraData
                    )
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
                    _notificationsState.value = NotificationsUiState.Success(list)
                }
                .onFailure { e ->
                    _notificationsState.value = NotificationsUiState.Error(e.message ?: "Error desconocido")
                }
            _isRefreshing.value = false
        }
    }

    fun updateUnreadCount() {
        viewModelScope.launch {
            notificationRepository.updateUnreadCount()
        }
    }

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            notificationRepository.markAsRead(notificationId)
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
            notificationRepository.markAllAsRead()
            updateUnreadCount()
            loadNotifications()
        }
    }

    fun deleteNotification(notificationId: String) {
        viewModelScope.launch {
            val result = notificationRepository.deleteNotification(notificationId)
            if (result.isSuccess) {
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
                _notificationsState.value = NotificationsUiState.Success(emptyList())
                updateUnreadCount()
            }
        }
    }
}
