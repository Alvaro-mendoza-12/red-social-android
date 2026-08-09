package conectate.com.presentation.algorithm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.algorithm.AlgorithmApiException
import conectate.com.data.model.AlgorithmCapabilities
import conectate.com.data.model.AlgorithmConfig
import conectate.com.data.model.AlgorithmDataPermissions
import conectate.com.data.model.AlgorithmFeedMix
import conectate.com.data.model.AlgorithmPreference
import conectate.com.data.model.AlgorithmPreferenceInput
import conectate.com.data.model.AlgorithmProfile
import conectate.com.data.model.AlgorithmSurface
import conectate.com.data.model.CreateAlgorithmProfileRequest
import conectate.com.data.model.CreateTemporaryAlgorithmPreferenceRequest
import conectate.com.data.repository.AlgorithmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class AlgorithmSettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val capabilities: AlgorithmCapabilities = AlgorithmCapabilities(),
    val config: AlgorithmConfig? = null,
    val profiles: List<AlgorithmProfile> = emptyList(),
    val usingCachedConfiguration: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val isFeatureAvailable: Boolean get() = capabilities.enabled
    val activeProfile: AlgorithmProfile? get() = config?.profile
}

/** Android is only a configuration editor; ranking and conflict resolution live on the server. */
@HiltViewModel
class AlgorithmSettingsViewModel @Inject constructor(
    private val repository: AlgorithmRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AlgorithmSettingsUiState())
    val state: StateFlow<AlgorithmSettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh(profileId: String? = null) {
        viewModelScope.launch {
            val cached = repository.loadCachedConfig()
            _state.update {
                it.copy(
                    isLoading = true,
                    config = cached ?: it.config,
                    usingCachedConfiguration = cached != null,
                    error = null,
                    message = null,
                )
            }
            val capabilities = repository.loadCapabilities()
            capabilities.fold(
                onSuccess = { value ->
                    _state.update { it.copy(capabilities = value) }
                    if (!value.enabled) {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                message = "El servidor no confirmó Mi algoritmo. Reintenta para cargar tus reglas.",
                            )
                        }
                        return@launch
                    }
                    loadLiveConfiguration(profileId)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = safeMessage(error),
                            usingCachedConfiguration = cached != null,
                        )
                    }
                },
            )
        }
    }

    fun selectProfile(profileId: String) = refresh(profileId)

    fun createProfile(name: String, mode: String) = mutate {
        repository.createProfile(CreateAlgorithmProfileRequest(name = name.trim(), mode = mode))
    }

    fun setProfileEnabled(enabled: Boolean) = mutateWithProfile { profile ->
        repository.updateProfile(profile.id, profile.revision, isEnabled = enabled)
    }

    fun setMode(mode: String) = mutateWithProfile { profile ->
        repository.updateProfile(profile.id, profile.revision, mode = mode)
    }

    fun makeDefault() = mutateWithProfile { profile ->
        repository.updateProfile(profile.id, profile.revision, makeDefault = true)
    }

    fun upsertPreference(input: AlgorithmPreferenceInput) = mutateWithProfile { profile ->
        val current = _state.value.config?.preferences.orEmpty()
        val merged = current
            .filterNot {
                it.surface == input.surface &&
                    it.dimension == input.dimension &&
                    it.subjectKey.equals(input.subjectKey, ignoreCase = true)
            }
            .map { existing ->
                AlgorithmPreferenceInput(
                    surface = existing.surface,
                    dimension = existing.dimension,
                    subjectKey = existing.subjectKey,
                    subjectLabel = existing.subjectLabel,
                    score = existing.score,
                )
            } + input
        repository.replacePreferences(profile.id, profile.revision, merged)
    }

    fun deletePreference(preference: AlgorithmPreference) = mutateWithProfile { profile ->
        repository.deletePreference(profile.id, preference.id, profile.revision)
    }

    fun updateFeedMix(mix: AlgorithmFeedMix) = mutateWithProfile { profile ->
        repository.updateFeedMix(profile.id, profile.revision, mix)
    }

    fun updateDataPermissions(permissions: AlgorithmDataPermissions) = mutateWithProfile { profile ->
        repository.updateDataPermissions(profile.id, profile.revision, permissions)
    }

    fun addTemporaryPreference(
        input: AlgorithmPreferenceInput,
        durationHours: Long,
    ) = mutateWithProfile { profile ->
        val expiresAt = Instant.now().plus(durationHours.coerceIn(1, 24L * 90L), ChronoUnit.HOURS).toString()
        repository.addTemporaryPreference(
            profile.id,
            CreateTemporaryAlgorithmPreferenceRequest(
                expectedVersion = profile.revision,
                surface = input.surface,
                dimension = input.dimension,
                subjectKey = input.subjectKey,
                subjectLabel = input.subjectLabel,
                score = input.score,
                expiresAt = expiresAt,
            ),
        )
    }

    fun deleteTemporaryPreference(preference: AlgorithmPreference) = mutateWithProfile { profile ->
        repository.deleteTemporaryPreference(profile.id, preference.id, profile.revision)
    }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    private suspend fun loadLiveConfiguration(profileId: String?) {
        val config = repository.loadConfig(profileId)
        val profiles = repository.loadProfiles()
        config.fold(
            onSuccess = { confirmed ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        config = confirmed,
                        profiles = profiles.getOrDefault(it.profiles),
                        usingCachedConfiguration = false,
                        error = null,
                    )
                }
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        profiles = profiles.getOrDefault(it.profiles),
                        usingCachedConfiguration = it.config != null,
                        error = safeMessage(error),
                    )
                }
            },
        )
    }

    private fun mutate(block: suspend () -> Result<AlgorithmConfig>) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, message = null) }
            block().fold(
                onSuccess = { config ->
                    _state.update {
                        it.copy(
                            isSaving = false,
                            config = config,
                            usingCachedConfiguration = false,
                            message = "Cambios guardados.",
                        )
                    }
                    loadLiveConfiguration(config.profile.id)
                },
                onFailure = { error -> handleMutationFailure(error) },
            )
        }
    }

    private fun mutateWithProfile(block: suspend (AlgorithmProfile) -> Result<AlgorithmConfig>) {
        val profile = _state.value.activeProfile
        if (profile == null) {
            _state.update { it.copy(error = "Primero carga una configuración de algoritmo.") }
            return
        }
        mutate { block(profile) }
    }

    private fun handleMutationFailure(error: Throwable) {
        val conflict = (error as? AlgorithmApiException)?.code == "ALGORITHM_PROFILE_CONFLICT"
        _state.update {
            it.copy(
                isSaving = false,
                error = if (conflict) {
                    "Tu algoritmo cambió en otro dispositivo. Se recargó la versión más reciente."
                } else {
                    safeMessage(error)
                },
            )
        }
        if (conflict) refresh(_state.value.activeProfile?.id)
    }

    private fun safeMessage(error: Throwable): String = when ((error as? AlgorithmApiException)?.code) {
        "USER_ALGORITHM_DISABLED", "USER_ALGORITHM_CAPABILITY_DISABLED" ->
            "El servidor no confirmó Mi algoritmo. Reintenta para cargar tus reglas."
        "ALGORITHM_RATE_LIMITED" -> "Espera un momento antes de volver a cambiar tus reglas."
        "ALGORITHM_PROFILE_CONFLICT" -> "La configuración cambió en otro dispositivo."
        "AUTH_REQUIRED" -> "Necesitas iniciar sesión para administrar tu algoritmo."
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: "No se pudo actualizar Mi algoritmo."
    }
}
