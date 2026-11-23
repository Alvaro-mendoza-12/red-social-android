package conectate.com.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import conectate.com.data.model.SharedMessageDestination
import conectate.com.data.model.SharedMessagePreview
import conectate.com.data.model.SharedMessageUnavailable
import conectate.com.data.repository.SharedMessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@HiltViewModel
class SharedPostMessageViewModel @Inject constructor(internal val repository: SharedMessageRepository) : ViewModel()

private sealed interface SharedPostCardState {
    data object Initial : SharedPostCardState
    data object Loading : SharedPostCardState
    data object Unavailable : SharedPostCardState
    data object Failed : SharedPostCardState
    data class Available(val preview: SharedMessagePreview) : SharedPostCardState
}

@Composable
internal fun SharedPostMessageCard(
    destination: SharedMessageDestination,
    scopeId: String,
    messageId: String,
    note: String,
    currentUserId: String?,
    enabled: Boolean,
    onOpen: (String) -> Unit,
) {
    val repository = hiltViewModel<SharedPostMessageViewModel>().repository
    val visit by repository.accountVisits.collectAsState()
    val latestOpen by rememberUpdatedState(onOpen)
    val latestEnabled by rememberUpdatedState(enabled)
    key(destination, scopeId, messageId, visit, currentUserId) {
        val owner = remember { repository.captureAccount() }
        if (owner.accountId.equals(currentUserId, ignoreCase = true) && owner.isCurrent()) {
            val scope = rememberCoroutineScope()
            var state by remember { mutableStateOf<SharedPostCardState>(SharedPostCardState.Initial) }
            val visible = (state as? SharedPostCardState.Available)?.preview
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(8.dp)) {
                    Text(visible?.title ?: "Publicación compartida", style = MaterialTheme.typography.titleSmall)
                    when (state) {
                        SharedPostCardState.Loading -> Text("Comprobando acceso…")
                        SharedPostCardState.Unavailable -> Text("La publicación ya no está disponible para esta cuenta.")
                        SharedPostCardState.Failed -> Text("No se pudo comprobar el acceso. Reintenta.")
                        else -> if (!visible?.summary.isNullOrBlank()) {
                            Text(visible!!.summary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        enabled = enabled && state != SharedPostCardState.Loading,
                        onClick = {
                            if (!latestEnabled || state == SharedPostCardState.Loading || !owner.isCurrent()) return@TextButton
                            val open = state is SharedPostCardState.Available
                            state = SharedPostCardState.Loading
                            scope.launch {
                                try {
                                    val preview = repository.resolve(owner, destination, scopeId, messageId)
                                    owner.requireCurrent()
                                    state = SharedPostCardState.Available(preview)
                                    if (open && latestEnabled) {
                                        state = SharedPostCardState.Initial
                                        latestOpen(preview.reference.id)
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: SharedMessageUnavailable) { if (owner.isCurrent()) state = SharedPostCardState.Unavailable }
                                catch (_: Exception) { if (owner.isCurrent()) state = SharedPostCardState.Failed }
                            }
                        },
                    ) {
                        Text(when (state) {
                            is SharedPostCardState.Available -> "Abrir publicación"
                            SharedPostCardState.Failed, SharedPostCardState.Unavailable -> "Reintentar"
                            else -> "Ver publicación compartida"
                        })
                    }
                }
            }
        }
    }
}