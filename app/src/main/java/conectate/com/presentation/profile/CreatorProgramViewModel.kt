package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.CreatorProgramDashboard
import conectate.com.data.repository.CreatorProgramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreatorProgramUiState(
    val isLoading: Boolean = false,
    val dashboard: CreatorProgramDashboard = CreatorProgramDashboard(),
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class CreatorProgramViewModel @Inject constructor(
    private val repository: CreatorProgramRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreatorProgramUiState(isLoading = true))
    val uiState: StateFlow<CreatorProgramUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.loadDashboard()
                .onSuccess { dashboard ->
                    _uiState.value = _uiState.value.copy(isLoading = false, dashboard = dashboard)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "No se pudo cargar el programa de creadores."
                    )
                }
        }
    }

    fun submitApplication(
        niche: String,
        portfolioUrl: String?,
        payoutCountry: String?,
        audienceSize: Int
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)
            repository.submitApplication(niche, portfolioUrl, payoutCountry, audienceSize)
                .onSuccess { application ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        dashboard = _uiState.value.dashboard.copy(application = application),
                        message = "Solicitud enviada. Estado: ${application.status}."
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "No se pudo enviar la solicitud."
                    )
                }
        }
    }

    fun savePayout(provider: String, externalAccountId: String?, country: String?, currency: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)
            repository.savePayoutAccount(provider, externalAccountId, country, currency)
                .onSuccess { payout ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        dashboard = _uiState.value.dashboard.copy(payoutAccount = payout),
                        message = "Método de pago guardado para revisión."
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "No se pudo guardar el método de pago."
                    )
                }
        }
    }

    fun clearTransientMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }
}
