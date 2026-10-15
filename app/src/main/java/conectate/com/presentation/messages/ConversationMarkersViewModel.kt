package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.BackendRouteUnavailableException
import conectate.com.data.localfallback.MessageMarkersResponse
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@HiltViewModel
class ConversationMarkersViewModel @Inject constructor(
    private val authService: AuthService,
    private val api: LocalFallbackApiClient,
    private val realtime: UbuntuRealtimeClient,
) : ViewModel() {
    private var target: Pair<String, String>? = null
    private var markerChannel: String? = null
    private var userChannel: String? = null
    private var boundUserId: String? = null
    private var refreshRevision = 0L
    private var markersUnavailable = false
    private val _markers = MutableStateFlow(MessageMarkersResponse())
    val markers = _markers.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback = _feedback.asStateFlow()

    init {
        viewModelScope.launch {
            realtime.events.collect { event ->
                val (kind, roomId) = target ?: return@collect
                if (event.event != "message_marker") return@collect
                if (event.channel != markerChannel && event.channel != userChannel) return@collect
                val eventKind = event.payload["kind"]?.jsonPrimitive?.contentOrNull
                val eventRoomId = event.payload["roomId"]?.jsonPrimitive?.contentOrNull
                if (kind == eventKind && roomId.equals(eventRoomId, ignoreCase = true)) refresh()
            }
        }
    }

    fun bind(kind: String, roomId: String) {
        val next = kind to roomId
        if (target == next) return
        markerChannel?.let(realtime::unsubscribe)
        target = next
        markerChannel = "message-markers:$kind:$roomId"
        _markers.value = MessageMarkersResponse()
        refresh()
    }

    fun refresh() = refresh(false)
    fun refreshFromUser() = refresh(true)
    private fun refresh(userInitiated: Boolean) {
        val (kind, roomId) = target ?: return
        val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
        if (boundUserId != currentUserId) markersUnavailable = false
        if (markersUnavailable) return
        val revision = ++refreshRevision
        if (boundUserId != currentUserId) {
            boundUserId = currentUserId
            _markers.value = MessageMarkersResponse()
        }
        viewModelScope.launch {
            _loading.value = true
            val token = authService.ensureValidAccessToken().getOrElse {
                if (revision == refreshRevision) {
                    _feedback.value = conversationMarkerFailureFeedback(it, userInitiated)
                    _loading.value = false
                }
                return@launch
            }
            if (revision != refreshRevision || target != (kind to roomId) || boundUserId != currentUserId) return@launch
            realtime.updateAccessToken(token)
            markerChannel?.let(realtime::subscribe)
            currentUserId?.takeIf(String::isNotBlank)?.let { userId ->
                userChannel = "user:$userId"
                realtime.replaceUserSubscription(userId)
            }
            val result = api.getMessageMarkers(token, kind, roomId)
            if (revision == refreshRevision && target == (kind to roomId) && boundUserId == currentUserId) {
                result.fold(
                    onSuccess = { _markers.value = it },
                    onFailure = {
                        if (it is BackendRouteUnavailableException) {
                            // The chat itself remains usable while an older backend is deployed.
                            markersUnavailable = true
                            _feedback.value = null
                        } else {
                            _feedback.value = conversationMarkerFailureFeedback(it, userInitiated)
                        }
                    },
                )
                _loading.value = false
            }
        }
    }

    fun togglePin(messageId: String) = mutate(messageId, "pin", _markers.value.pins.none { it.messageId == messageId })

    fun toggleSaved(messageId: String, label: String = "") =
        mutate(messageId, "saved", _markers.value.saved.none { it.messageId == messageId }, label)

    fun setSavedLabel(messageId: String, label: String) = mutate(messageId, "saved", true, label)

    private fun mutate(messageId: String, marker: String, enabled: Boolean, label: String = "") {
        val (kind, roomId) = target ?: return
        if (markersUnavailable) {
            _feedback.value = BackendRouteUnavailableException().message
            return
        }
        if (_loading.value) return
        val accountId = boundUserId
        viewModelScope.launch {
            _loading.value = true
            val token = authService.ensureValidAccessToken().getOrElse {
                _feedback.value = "Inicia sesión para modificar mensajes guardados."
                _loading.value = false
                return@launch
            }
            if (target != (kind to roomId) || boundUserId != accountId ||
                authService.getCurrentUserId()?.trim()?.lowercase() != accountId) return@launch
            val result = api.updateMessageMarker(token, kind, roomId, messageId, marker, enabled, label)
            if (target != (kind to roomId) || boundUserId != accountId) return@launch
            result.fold(
                onSuccess = {
                    _markers.value = it
                    _feedback.value = when {
                        marker == "pin" && enabled -> "Mensaje fijado para la conversación."
                        marker == "pin" -> "Mensaje desfijado."
                        enabled -> "Mensaje guardado solo para ti."
                        else -> "Mensaje quitado de guardados."
                    }
                },
                onFailure = { error ->
                    _feedback.value = when {
                        error.message?.contains("PIN_LIMIT_REACHED") == true -> "Solo se pueden fijar 10 mensajes por conversación."
                        error.message?.contains("PIN_REQUIRES_ADMIN") == true -> "Solo administradores pueden fijar mensajes aquí."
                        else -> conversationMarkerFailureFeedback(error, true)
                    }
                },
            )
            _loading.value = false
        }
    }

    fun clearFeedback() { _feedback.value = null }

    override fun onCleared() {
        markerChannel?.let(realtime::unsubscribe)
        super.onCleared()
    }
}
