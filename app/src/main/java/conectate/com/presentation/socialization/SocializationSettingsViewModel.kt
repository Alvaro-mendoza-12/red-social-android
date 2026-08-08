package conectate.com.presentation.socialization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.SocializationCapabilities
import conectate.com.data.model.SocializationPreferenceKey
import conectate.com.data.model.SocializationPreferences
import conectate.com.data.socialization.SocializationApiException
import conectate.com.data.repository.SocializationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SocializationSettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val capabilities: SocializationCapabilities = SocializationCapabilities(),
    val preferences: SocializationPreferences = SocializationPreferences(),
    val usingCachedPreferences: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    /** The core backend flag is the only authority for editing consent. */
    val canEditConsent: Boolean get() = capabilities.enabled && !isSaving
}

@HiltViewModel
class SocializationSettingsViewModel @Inject constructor(
    private val repository: SocializationRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SocializationSettingsUiState())
    val state: StateFlow<SocializationSettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val cached = repository.loadCachedPreferences()
            _state.update {
                it.copy(
                    isLoading = true,
                    preferences = cached?.preferences ?: it.preferences,
                    usingCachedPreferences = cached != null,
                    error = null,
                    message = null,
                )
            }
            repository.loadCapabilities().fold(
                onSuccess = { capabilities ->
                    _state.update { it.copy(capabilities = capabilities) }
                    if (!capabilities.enabled) {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                message = "Socializar está preparado, pero todavía no se habilitó para este grupo de pruebas.",
                            )
                        }
                    } else {
                        loadConfirmedPreferences(cached != null)
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            usingCachedPreferences = cached != null,
                            error = safeMessage(error),
                        )
                    }
                },
            )
        }
    }

    fun setConsent(key: SocializationPreferenceKey, enabled: Boolean) {
        val current = _state.value
        if (!current.canEditConsent) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, message = null) }
            repository.updatePreference(current.preferences.revision, key, enabled).fold(
                onSuccess = { response ->
                    _state.update {
                        it.copy(
                            isSaving = false,
                            preferences = response.preferences,
                            capabilities = it.capabilities.copy(features = response.capabilities),
                            usingCachedPreferences = false,
                            message = "Preferencia guardada.",
                        )
                    }
                },
                onFailure = { error -> handleMutationError(error) },
            )
        }
    }

    fun dismissMessage() = _state.update { it.copy(error = null, message = null) }

    private suspend fun loadConfirmedPreferences(hadCache: Boolean) {
        repository.loadPreferences().fold(
            onSuccess = { response ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        preferences = response.preferences,
                        capabilities = it.capabilities.copy(features = response.capabilities),
                        usingCachedPreferences = false,
                    )
                }
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        usingCachedPreferences = hadCache,
                        error = safeMessage(error),
                    )
                }
            },
        )
    }

    private fun handleMutationError(error: Throwable) {
        val conflict = (error as? SocializationApiException)?.code == "SOCIALIZATION_PREFERENCES_CONFLICT"
        _state.update {
            it.copy(
                isSaving = false,
                error = if (conflict) {
                    "Estas preferencias cambiaron en otro dispositivo. Se cargó la versión más reciente."
                } else {
                    safeMessage(error)
                },
            )
        }
        if (conflict) refresh()
    }

    private fun safeMessage(error: Throwable): String = when ((error as? SocializationApiException)?.code) {
        "SOCIALIZATION_DISABLED" -> "Socializar aún no está habilitado para este grupo."
        "SOCIALIZATION_RATE_LIMITED" -> "Espera un momento antes de cambiar otra preferencia."
        "BACKEND_UNAVAILABLE" -> "No hay conexión con el servidor. Se conserva la última configuración confirmada."
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: "No se pudo actualizar la preferencia. Inténtalo nuevamente."
    }
}
