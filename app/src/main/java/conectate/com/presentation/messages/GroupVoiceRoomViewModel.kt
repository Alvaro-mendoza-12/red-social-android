package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.groupvoice.*
import conectate.com.data.repository.GroupVoiceRepository
import conectate.com.data.supabase.AuthSessionLease
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

data class GroupVoiceDraftState(
    val roster: GroupVoiceRoster? = null,
    val mode: String = "all",
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val creating: Boolean = false,
    val pending: Boolean = false,
    val error: String? = null,
    val roomId: String? = null,
    val sessionChanged: Boolean = false,
) {
    val count: Int get() = if (mode == "all" && !pending) roster?.members?.size ?: 0 else selected.size
}

@HiltViewModel
class GroupVoiceRoomViewModel @Inject constructor(private val repository: GroupVoiceRepository) : ViewModel() {
    private val mutable = MutableStateFlow(GroupVoiceDraftState())
    val state = mutable.asStateFlow()
    private var group = ""
    private var owner: AuthSessionLease? = null
    private var job: Job? = null
    private var intent: GroupVoiceIntent? = null
    init {
        viewModelScope.launch {
            repository.visits().collect { visit ->
                if (owner != null && owner?.sameVisit(visit) != true) {
                    job?.cancel(); owner = null
                    mutable.value = GroupVoiceDraftState(loading = false, sessionChanged = true)
                }
            }
        }
    }
    private fun publish(lease: AuthSessionLease, update: () -> Unit): Boolean =
        owner === lease && repository.publish(lease, update)
    fun open(groupId: String) {
        close()
        group = groupId
        val lease = repository.capture().also { owner = it }
        mutable.value = GroupVoiceDraftState()
        job = viewModelScope.launch {
            try {
                val restored = repository.pending(lease, groupId)
                if (owner !== lease) return@launch
                intent = restored
                val roster = repository.roster(lease, groupId).getOrThrow()
                publish(lease) {
                    mutable.value = GroupVoiceDraftState(roster, intent?.mode ?: "all", intent?.recipientIds?.toSet() ?: emptySet(),
                        loading = false, pending = intent != null,
                        error = if (intent != null) "Hay una invitación pendiente de confirmar. Reintenta para recuperar la misma sala." else null)
                }
            } catch (timeout: TimeoutCancellationException) {
                publish(lease) { mutable.value = mutable.value.copy(loading = false, error = "La carga tardó demasiado. Cierra y vuelve a intentar.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { publish(lease) { mutable.value = mutable.value.copy(loading = false, error = message(error)) } }
        }
    }
    fun mode(value: String) { if (editable()) mutable.value = mutable.value.copy(mode = value) }
    fun toggle(id: String) { if (editable()) mutable.value = mutable.value.copy(selected = mutable.value.selected.let { if (id in it) it - id else it + id }) }
    fun all() { if (editable()) mutable.value = mutable.value.copy(selected = mutable.value.roster?.members.orEmpty().map { it.id }.toSet().let { if (it == mutable.value.selected) emptySet() else it }) }
    private fun editable() = !mutable.value.loading && !mutable.value.creating && !mutable.value.pending
    fun create() {
        val lease = owner ?: return
        val current = mutable.value
        if (current.loading || current.creating || current.count == 0) return
        val destination = group
        val attempt = intent ?: GroupVoiceIntent(UUID.randomUUID().toString(), current.mode,
            (if (current.mode == "all") current.roster?.members.orEmpty().map { it.id } else current.selected.toList()).sorted()).also { intent = it }
        if (!publish(lease) { mutable.value = current.copy(creating = true, pending = true, error = null) }) return
        job = viewModelScope.launch {
            try {
                val response = repository.create(lease, destination, attempt).getOrThrow()
                publish(lease) { mutable.value = mutable.value.copy(roomId = response.roomId) }
            } catch (timeout: TimeoutCancellationException) {
                publish(lease) { mutable.value = mutable.value.copy(error = "No llegó la confirmación. Reintenta para recuperar la misma sala.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (owner !== lease) return@launch
                val code = (error as? GroupVoiceApiException)?.code
                if (code in setOf("GROUP_VOICE_MEMBERS_CHANGED", "GROUP_VOICE_ROOM_ENDED", "GROUP_VOICE_CREATE_LIMIT")) {
                    intent = repository.pending(lease, destination)
                }
                publish(lease) { mutable.value = mutable.value.copy(error = message(error), pending = intent != null) }
            } finally { publish(lease) { mutable.value = mutable.value.copy(creating = false) } }
        }
    }
    fun close() { job?.cancel(); job = null; owner = null; intent = null }
    private fun message(error: Exception) = when ((error as? GroupVoiceApiException)?.code) {
        "GROUP_VOICE_MEMBERS_CHANGED" -> "Cambiaron los integrantes. Cierra y vuelve a abrir para revisar a quién invitar."
        "GROUP_VOICE_MEMBERSHIP_REQUIRED" -> "Debes seguir siendo integrante de este grupo."
        "GROUP_VOICE_ROOM_ENDED" -> "La sala ya terminó. Puedes preparar otra invitación."
        "GROUP_VOICE_CREATE_LIMIT" -> "Has creado varias salas. Espera unos minutos para crear otra."
        else -> "No se pudo confirmar la sala. Reintenta: no enviaremos invitaciones duplicadas."
    }
}
