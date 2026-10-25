package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.ProfileMusicSummary
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class ProfileMusicState(val summary: ProfileMusicSummary? = null, val error: String? = null, val available: Boolean = true)

@HiltViewModel
class ProfileMusicViewModel @Inject constructor(
    private val api: LocalFallbackApiClient,
    private val auth: AuthService,
    private val realtime: UbuntuRealtimeClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProfileMusicState())
    internal val state = mutableState.asStateFlow()
    private var target = ""
    private var source = ""
    private var owner: String? = null
    private var epoch = 0L
    private var loadJob: Job? = null
    private var styleJob: Job? = null
    private var visible = false
    private val channel get() = "profile-music:$target"
    private val writer = ProfileMusicWriter(viewModelScope, { mutableState.value.summary },
        { summary, reaction ->
            val account = auth.getCurrentUserId()
            val token = auth.getAccessTokenAwait() ?: return@ProfileMusicWriter Result.failure(Exception("Inicia sesión para reaccionar"))
            if (account == null || account != auth.getCurrentUserId()) Result.failure(Exception("La sesión ha cambiado"))
            else api.updateProfileMusic(token, summary.profileId, summary.musicKey, reaction = reaction)
        },
        { mutableState.value = ProfileMusicState(it.copy(presentation = mutableState.value.summary?.presentation ?: it.presentation)) },
        {
            mutableState.value = mutableState.value.copy(error = "No se pudo guardar la reacción. Reintenta.")
            viewModelScope.launch { kotlinx.coroutines.yield(); refresh() }
        },
    )

    init {
        viewModelScope.launch {
            auth.currentSession.map { auth.getCurrentUserId() }.distinctUntilChanged().collect { account ->
                if (owner != account) { reset(); owner = account; if (visible) { realtime.subscribe(channel); refresh() } }
            }
        }
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (visible && event.channel.equals(channel, true) &&
                    event.event in setOf("subscription_ready", "profile_music_updated")) refresh()
            }
        }
    }

    internal fun attach(profileId: String, musicUrl: String) {
        val id = profileId.trim().lowercase()
        if (target != id || source != musicUrl || owner != auth.getCurrentUserId()) {
            if (visible) realtime.unsubscribe(channel)
            reset(); target = id; source = musicUrl; owner = auth.getCurrentUserId()
        }
        visible = true
        if (target.isNotBlank()) { realtime.subscribe(channel); refresh() }
    }

    internal fun detach() { visible = false; realtime.unsubscribe(channel) }
    private fun reset() { epoch++; loadJob?.cancel(); styleJob?.cancel(); writer.clear(); mutableState.value = ProfileMusicState() }

    internal fun refresh() {
        if (!visible || target.isBlank() || writer.pending || styleJob?.isActive == true || loadJob?.isActive == true) return
        val ticket = epoch
        val account = auth.getCurrentUserId()
        val id = target
        loadJob = viewModelScope.launch {
            try {
                val token = auth.getAccessTokenAwait() ?: error("Inicia sesión para reaccionar")
                val result = api.loadProfileMusic(token, id).getOrThrow()
                if (ticket == epoch && account == auth.getCurrentUserId() && !writer.pending && styleJob?.isActive != true)
                    mutableState.value = ProfileMusicState(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == epoch && account == auth.getCurrentUserId()) {
                    mutableState.value = if (error is conectate.com.data.model.ProfileMusicUnavailableException)
                        ProfileMusicState(error = error.message, available = false)
                    else mutableState.value.copy(error = "No se pudieron cargar las reacciones. Reintentar")
                }
            }
        }
    }

    internal fun react(value: String) { loadJob?.cancel(); writer.select(value) }

    internal fun presentation(value: String) {
        val before = mutableState.value.summary ?: return
        if (!before.canEditPresentation || styleJob?.isActive == true || value !in setOf("audio", "disc")) return
        val ticket = epoch
        val account = auth.getCurrentUserId()
        val id = target
        loadJob?.cancel()
        mutableState.value = ProfileMusicState(before.copy(presentation = value))
        styleJob = viewModelScope.launch {
            try {
                val token = auth.getAccessTokenAwait() ?: error("Inicia sesión")
                val result = api.updateProfileMusic(token, id, before.musicKey, presentation = value).getOrThrow()
                if (ticket == epoch && account == auth.getCurrentUserId()) {
                    // A reaction may have been tapped during this request. Do not overwrite that intent.
                    mutableState.value = mutableState.value.copy(summary =
                        mutableState.value.summary?.copy(presentation = result.presentation))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == epoch && account == auth.getCurrentUserId()) mutableState.value = mutableState.value.copy(
                    summary = mutableState.value.summary?.copy(presentation = before.presentation),
                    error = "No se pudo guardar la presentación. Reintenta.",
                )
            }
        }
    }

    override fun onCleared() { if (visible) realtime.unsubscribe(channel); super.onCleared() }
}
