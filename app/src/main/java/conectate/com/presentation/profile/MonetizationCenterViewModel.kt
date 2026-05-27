package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.MonetizationDashboard
import conectate.com.data.model.SubscriptionPlan
import conectate.com.data.repository.MonetizationRepository
import conectate.com.data.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MonetizationCenterUiState(
    val isLoading: Boolean = false,
    val isSubscribing: Boolean = false,
    val dashboard: MonetizationDashboard = MonetizationDashboard(),
    val balanceDiamonds: Int = 0,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class MonetizationCenterViewModel @Inject constructor(
    private val repository: MonetizationRepository,
    private val walletRepository: WalletRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonetizationCenterUiState(isLoading = true))
    val uiState: StateFlow<MonetizationCenterUiState> = _uiState.asStateFlow()

    private val recordedImpressions = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            walletRepository.coinBalance.collect { balance ->
                _uiState.update { it.copy(balanceDiamonds = balance) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            walletRepository.fetchBalance()
            repository.loadDashboard()
                .onSuccess { dashboard ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            dashboard = dashboard,
                            error = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "No se pudo cargar monetización."
                        )
                    }
                }
        }
    }

    fun subscribe(plan: SubscriptionPlan) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubscribing = true, error = null, message = null) }
            repository.subscribeToPlan(plan.id)
                .onSuccess { subscription ->
                    walletRepository.fetchBalance()
                    repository.loadDashboard()
                        .onSuccess { dashboard ->
                            _uiState.update {
                                it.copy(
                                    isSubscribing = false,
                                    dashboard = dashboard,
                                    message = "Premium activado: ${subscription.planName ?: plan.name}.",
                                    error = null
                                )
                            }
                        }
                        .onFailure {
                            _uiState.update {
                                it.copy(
                                    isSubscribing = false,
                                    dashboard = it.dashboard.copy(currentSubscription = subscription),
                                    message = "Premium activado: ${subscription.planName ?: plan.name}.",
                                    error = null
                                )
                            }
                        }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSubscribing = false,
                            error = error.message ?: "No se pudo activar Premium."
                        )
                    }
                }
        }
    }

    fun recordAdImpression(campaignId: String) {
        val cleanCampaignId = campaignId.trim()
        if (cleanCampaignId.isBlank() || !recordedImpressions.add(cleanCampaignId)) return
        viewModelScope.launch {
            repository.recordAdImpression(cleanCampaignId)
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }
}
