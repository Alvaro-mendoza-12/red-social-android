package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.LocalFallbackCollectionPreview
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CollectionQrTarget(
    val kind: String,
    val roomId: String,
    val inviteToken: String? = null
)

data class CollectionQrUiState(
    val isLoading: Boolean = false,
    val isJoining: Boolean = false,
    val target: CollectionQrTarget? = null,
    val preview: LocalFallbackCollectionPreview? = null,
    val error: String? = null,
    val joinedTarget: CollectionQrTarget? = null
)

internal object CollectionQrParser {
    private val allowedWebHosts = setOf(
        "conectate.com",
        "www.conectate.com",
    ) + conectate.com.data.model.PublicWebDomain.httpsHosts

    fun parse(rawValue: String): Result<CollectionQrTarget> = runCatching {
        val raw = rawValue.trim()
        require(raw.length <= 2048) { "El código QR no es una invitación válida." }
        require(raw.isNotBlank()) { "El código QR está vacío." }
        val uri = URI(raw)
        require(uri.userInfo == null && uri.port == -1 && uri.fragment == null) { "El enlace del QR no es válido." }
        val scheme = uri.scheme?.lowercase().orEmpty()
        val pathSegments = uri.path.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }

        val kind: String
        val roomId: String
        when (scheme) {
            "https", "http" -> {
                require(uri.host?.lowercase() in allowedWebHosts) {
                    "Este QR no pertenece a Novvin."
                }
                require(uri.host?.lowercase() !in conectate.com.data.model.PublicWebDomain.httpsHosts || scheme == "https") {
                    "La invitación debe usar HTTPS."
                }
                require(pathSegments.size == 2) {
                    "El enlace del QR está incompleto."
                }
                kind = pathSegments[0].toCollectionKind()
                roomId = pathSegments[1]
            }
            "conectate" -> {
                kind = uri.host.orEmpty().toCollectionKind()
                require(pathSegments.size == 1) {
                    "El enlace del QR está incompleto."
                }
                roomId = pathSegments[0]
            }
            else -> error("Este QR no es un enlace compatible de Novvin.")
        }

        val cleanRoomId = runCatching { UUID.fromString(roomId).toString() }
            .getOrElse { error("El identificador del grupo o comunidad no es válido.") }
        val query = parseQuery(uri.rawQuery)
        val inviteToken = query["invite"]?.trim()?.takeIf { it.isNotBlank() }
        if (kind == "group") {
            require(!inviteToken.isNullOrBlank()) {
                "La invitación del grupo está incompleta."
            }
        }
        CollectionQrTarget(kind = kind, roomId = cleanRoomId, inviteToken = inviteToken)
    }

    private fun String.toCollectionKind(): String = when (lowercase()) {
        "group", "groups" -> "group"
        "community", "communities" -> "community"
        else -> error("El QR no corresponde a un grupo o comunidad.")
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
        .split('&')
        .mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val separator = pair.indexOf('=')
            val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
            val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
            decode(rawKey).lowercase() to decode(rawValue)
        }
        .toMap()

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

@HiltViewModel
class CollectionQrViewModel @Inject constructor(
    private val apiClient: LocalFallbackApiClient,
    private val authService: AuthService
) : ViewModel() {
    private var operation = 0L
    private var previewAccount: String? = null
    private val _uiState = MutableStateFlow(CollectionQrUiState())
    val uiState: StateFlow<CollectionQrUiState> = _uiState.asStateFlow()

    fun inspect(rawValue: String) {
        val request = ++operation
        val account = authService.getCurrentUserId()
        previewAccount = null
        val target = CollectionQrParser.parse(rawValue).getOrElse { error ->
            _uiState.value = CollectionQrUiState(error = error.message ?: "No se pudo leer el QR.")
            return
        }
        viewModelScope.launch {
            _uiState.value = CollectionQrUiState(isLoading = true, target = target)
            val token = authService.getAccessTokenAwait()?.trim().orEmpty()
            if (request != operation || account != authService.getCurrentUserId()) return@launch
            if (token.isBlank()) {
                _uiState.value = CollectionQrUiState(error = "Tu sesión venció. Inicia sesión y vuelve a intentarlo.")
                return@launch
            }
            apiClient.previewCollection(token, target.kind, target.roomId, target.inviteToken)
                .onSuccess { preview ->
                    if (request != operation || account != authService.getCurrentUserId()) return@onSuccess
                    previewAccount = account
                    _uiState.value = CollectionQrUiState(target = target, preview = preview)
                }
                .onFailure { error ->
                    if (request != operation || account != authService.getCurrentUserId()) return@onFailure
                    _uiState.value = CollectionQrUiState(error = error.userMessage())
                }
        }
    }

    fun joinOrOpen() {
        val current = _uiState.value
        if (current.isJoining || current.isLoading) return
        val account = authService.getCurrentUserId()
        if (account == null || previewAccount != account) {
            _uiState.value = CollectionQrUiState(error = "Cambiaste de cuenta. Abre nuevamente la invitación.")
            return
        }
        val request = operation
        val target = current.target ?: return
        val preview = current.preview ?: return
        if (preview.isMember) {
            _uiState.value = current.copy(joinedTarget = target)
            return
        }
        viewModelScope.launch {
            _uiState.value = current.copy(isJoining = true, error = null)
            val token = authService.getAccessTokenAwait()?.trim().orEmpty()
            if (request != operation || account != authService.getCurrentUserId()) return@launch
            if (token.isBlank()) {
                _uiState.value = current.copy(isJoining = false, error = "Tu sesión venció. Inicia sesión y vuelve a intentarlo.")
                return@launch
            }
            val result = if (!target.inviteToken.isNullOrBlank()) {
                apiClient.joinCollectionWithInvite(token, if (target.kind == "group") "groups" else "communities", target.roomId, target.inviteToken)
            } else {
                apiClient.joinCollection(token, "communities", target.roomId)
            }
            result.onSuccess {
                if (request != operation || account != authService.getCurrentUserId()) return@onSuccess
                _uiState.value = current.copy(isJoining = false, joinedTarget = target)
            }.onFailure { error ->
                if (request != operation || account != authService.getCurrentUserId()) return@onFailure
                _uiState.value = current.copy(isJoining = false, error = error.userMessage())
            }
        }
    }

    fun dismiss() {
        if (!_uiState.value.isLoading && !_uiState.value.isJoining) {
            _uiState.value = CollectionQrUiState()
        }
    }

    fun consumeNavigation() {
        operation++
        previewAccount = null
        _uiState.value = CollectionQrUiState()
    }

    fun showCameraPermissionError() {
        _uiState.value = CollectionQrUiState(
            error = "Concede permiso de cámara para escanear códigos QR."
        )
    }

    fun showImageScanError() {
        _uiState.value = CollectionQrUiState(error = "No encontramos un código QR legible en esa imagen. Elige la imagen completa o escanéalo con la cámara.")
    }

    private fun Throwable.userMessage(): String {
        val detail = message?.trim().orEmpty()
        return when {
            "GROUP_INVITE_EXPIRED_OR_USED" in detail -> "Esta invitación caducó, fue revocada o alcanzó su límite. Pide un enlace nuevo."
            "GROUP_INVITE_REQUIRED" in detail -> "Necesitas una invitación válida para entrar. Pide el enlace a un integrante."
            "ROOM_NOT_FOUND" in detail || "GROUP_NOT_FOUND" in detail || "COMMUNITY_NOT_FOUND" in detail -> "El grupo o la comunidad ya no está disponible."
            detail.isBlank() || Regex("^[A-Z_0-9]+$").matches(detail) -> "No se pudo completar la operación. Intenta nuevamente."
            else -> detail
        }
    }
}
