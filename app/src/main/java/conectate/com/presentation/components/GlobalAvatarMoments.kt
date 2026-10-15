package conectate.com.presentation.components

import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.Moment
import conectate.com.data.model.activeAvatarMoments
import conectate.com.data.repository.MomentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

internal class AvatarMomentAccess(
    val moments: Map<String, Moment>,
    val owner: AvatarMomentRegistrar,
    val open: (Moment) -> Unit,
)
internal val LocalAvatarMoments = compositionLocalOf<AvatarMomentAccess?> { null }
internal interface AvatarMomentRegistrar {
    fun register(owner: String): String
    fun unregister(key: String)
}

/** One batched request for visible owners across every application section. */
@HiltViewModel
internal class GlobalAvatarMomentViewModel @Inject constructor(private val repository: MomentRepository) : ViewModel(), AvatarMomentRegistrar {
    val moments = repository.moments
    val momentAccount = repository.momentAccount
    private val registrations = MutableStateFlow<Map<String, String>>(emptyMap())
    private var account: String? = null
    init {
        viewModelScope.launch {
            registrations.collectLatest { entries ->
                delay(300L)
                account?.let { repository.refreshGlobalAvatarOwners(entries.values.distinct(), it) }
            }
        }
    }
    fun setAccount(value: String?) { if (account != value) { account = value; registrations.value = emptyMap() } }
    override fun register(owner: String): String = UUID.randomUUID().toString().also { registrations.value += it to owner }
    override fun unregister(key: String) { registrations.value -= key }
}

@Composable
internal fun GlobalAvatarMomentProvider(accountId: String?, onOpen: (Moment) -> Unit, content: @Composable () -> Unit) {
    if (accountId.isNullOrBlank()) { content(); return }
    val viewModel: GlobalAvatarMomentViewModel = hiltViewModel()
    val moments by viewModel.moments.collectAsStateWithLifecycle()
    val momentAccount by viewModel.momentAccount.collectAsStateWithLifecycle()
    val now by rememberMomentAvatarClock()
    DisposableEffect(accountId) { viewModel.setAccount(accountId); onDispose { viewModel.setAccount(null) } }
    val active = remember(moments, now, accountId, momentAccount) {
        if (accountId != momentAccount) emptyMap() else activeAvatarMoments(moments, now)
    }
    CompositionLocalProvider(
        LocalProfileIdentityAccount provides accountId,
        LocalAvatarMoments provides AvatarMomentAccess(active, viewModel, onOpen),
        content = content,
    )
}
