package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import conectate.com.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Retain the selected content through Activity recreation, without retaining an Activity. */
@HiltViewModel
class UniversalForwardViewModel @Inject constructor(authRepository: AuthRepository) : ViewModel() {
    val coordinator = UniversalForwardCoordinator(authRepository::captureAccountLease)
}
