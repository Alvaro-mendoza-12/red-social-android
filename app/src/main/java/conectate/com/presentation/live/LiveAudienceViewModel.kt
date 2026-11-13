package conectate.com.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.LiveAudienceApiClient
import conectate.com.data.localfallback.LiveAudienceException
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.LiveAudienceState
import conectate.com.data.repository.normalizeLiveSessionId
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.http.HttpMethod
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@HiltViewModel
class LiveAudienceViewModel internal constructor(
    private val requestApi: suspend (String, String, String, HttpMethod, JsonObject?) -> Result<LiveAudienceState>,
    private val captureOwner: () -> AuthSessionLease,
    private val ensureToken: suspend () -> Result<String>,
    private val commitOwner: (AuthSessionLease, () -> Unit) -> Boolean,
    private val realtime: UbuntuRealtimeClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    @Inject constructor(api: LiveAudienceApiClient, auth: AuthService, realtime: UbuntuRealtimeClient) : this(
        api::request, auth::captureAccountLease, { auth.ensureValidAccessToken() },
        auth::commitForAccountLease, realtime,
    )

    private val _state = MutableStateFlow<LiveAudienceState?>(null)
    val state = _state.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _createdVersion = MutableStateFlow(0)
    val createdVersion = _createdVersion.asStateFlow()
    private val requests = Mutex()
    private val sessionLock = Any()
    private var sessionJob: Job? = null
    private var actionJob: Job? = null
    private var retryJob: Job? = null
    @Volatile private var liveId: String? = null
    @Volatile private var accountOwner: AuthSessionLease? = null
    @Volatile private var activeOwner: AuthSessionLease? = null
    private var actionVersion = 0L
    private val pendingCreates = mutableMapOf<String, Pair<String, String>>()

    fun bind(id: String) {
        val normalized = normalizeLiveSessionId(id) ?: return
        val owner = captureOwner()
        if (!owner.isCurrent()) return
        val sameSession = liveId == normalized && accountOwner?.sameVisit(owner) == true
        if (sameSession && sessionJob?.isActive == true) return
        val previous = sessionJob
        stop()
        if (!sameSession) {
            _state.value = null
            _error.value = null
            pendingCreates.clear()
        }
        liveId = normalized
        accountOwner = owner
        activeOwner = owner
        sessionJob = viewModelScope.launch(ioDispatcher) {
            previous?.join()
            val token = ensureToken().getOrElse {
                commitSession(owner, normalized) { _error.value = "Vuelve a iniciar sesión para participar." }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            val channel = "live-audience:$normalized"
            if (!commitSession(owner, normalized) {
                realtime.updateAccessToken(token)
                realtime.subscribe(channel)
            }) return@launch
            try {
                coroutineScope {
                    launch {
                        realtime.events.filter { it.channel == channel }.conflate().collect { refresh(owner, normalized) }
                    }
                    while (isActive) {
                        refresh(owner, normalized)
                        delay(5_000)
                    }
                }
            } finally { commitOwner(owner) { realtime.unsubscribe(channel) } }
        }
    }

    fun stop() {
        synchronized(sessionLock) {
            activeOwner = null
            actionVersion++
            _busy.value = false
        }
        sessionJob?.cancel()
        actionJob?.cancel()
        retryJob?.cancel()
        actionJob = null
        retryJob = null
        // Keep the canceled session job so a resume waits for its unsubscribe.
        // Its account lease and nonces still belong to this same account visit.
    }

    fun retry() {
        val owner = activeOwner ?: return
        val id = liveId ?: return
        if (retryJob?.isActive == true) return
        retryJob = viewModelScope.launch(ioDispatcher) { refresh(owner, id) }
    }

    private fun commitSession(owner: AuthSessionLease, id: String, action: () -> Unit): Boolean {
        if (activeOwner !== owner || liveId != id) return false
        var applied = false
        commitOwner(owner) {
            synchronized(sessionLock) {
                if (activeOwner === owner && liveId == id) { action(); applied = true }
            }
        }
        return applied
    }

    private suspend fun refresh(owner: AuthSessionLease, id: String) = requests.withLock {
        currentCoroutineContext().ensureActive()
        if (!owner.isCurrent() || activeOwner !== owner || liveId != id) return@withLock
        val token = ensureToken().getOrElse {
            commitSession(owner, id) { _error.value = "Vuelve a iniciar sesión para participar." }
            return@withLock
        }
        currentCoroutineContext().ensureActive()
        owner.requireCurrent()
        if (activeOwner !== owner || liveId != id) return@withLock
        val result = requestApi(token, id, "", HttpMethod.Get, null)
        currentCoroutineContext().ensureActive()
        commitSession(owner, id) {
            result.onSuccess { _state.value = it; _error.value = null }.onFailure { _error.value = message(it) }
        }
    }

    fun createPoll(content: String, options: List<String>, seconds: Int) = create("polls", buildJsonObject {
        put("content", content.trim())
        put("options", JsonArray(options.map { JsonPrimitive(it.trim()) }))
        put("durationSeconds", seconds)
    })

    fun ask(content: String) = create("questions", buildJsonObject { put("content", content.trim()) })

    private fun create(kind: String, body: JsonObject) {
        if (_busy.value || activeOwner == null) return
        val payload = body.toString()
        val nonce = pendingCreates[kind]?.takeIf { it.first == payload }?.second ?: UUID.randomUUID().toString()
        pendingCreates[kind] = payload to nonce
        mutate("/$kind", HttpMethod.Post, JsonObject(body + ("requestNonce" to JsonPrimitive(nonce)))) {
            pendingCreates.remove(kind)
            _createdVersion.value++
        }
    }

    fun vote(item: String, choice: Int) = mutate("/items/$item/vote", HttpMethod.Post,
        buildJsonObject { put("choiceIndex", choice) })

    fun update(item: String, status: String? = null, answer: String? = null, highlighted: Boolean? = null) =
        mutate("/items/$item", HttpMethod.Patch, buildJsonObject {
            if (status != null) put("status", status)
            if (answer != null) put("answer", answer.trim())
            if (highlighted != null) put("highlighted", highlighted)
        })

    private fun mutate(path: String, method: HttpMethod, body: JsonObject, success: () -> Unit = {}) {
        if (_busy.value) return
        val id = liveId ?: return
        val owner = activeOwner ?: return
        if (!owner.isCurrent()) return
        _busy.value = true
        val version = ++actionVersion
        _error.value = null
        actionJob = viewModelScope.launch {
            try {
                requests.withLock {
                    currentCoroutineContext().ensureActive()
                    owner.requireCurrent()
                    if (id != liveId || activeOwner !== owner) return@withLock
                    val token = ensureToken().getOrThrow()
                    currentCoroutineContext().ensureActive()
                    owner.requireCurrent()
                    if (id != liveId || activeOwner !== owner) return@withLock
                    val result = requestApi(token, id, path, method, body).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    commitSession(owner, id) {
                        _state.value = result
                        success()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                commitSession(owner, id) { _error.value = message(error) }
            } finally {
                synchronized(sessionLock) { if (version == actionVersion) _busy.value = false }
            }
        }
    }

    private fun message(error: Throwable): String = when ((error as? LiveAudienceException)?.code) {
        "LIVE_POLL_ALREADY_OPEN" -> "Ya hay una encuesta abierta. Ciérrala para crear la siguiente."
        "LIVE_POLL_CLOSED" -> "Esta encuesta ya terminó. Actualiza para ver el resultado."
        "LIVE_POLL_OPTIONS_INVALID" -> "Escribe entre dos y seis respuestas diferentes."
        "LIVE_COMMENT_BLOCKED" -> "Revisa la pregunta. El directo tiene un filtro de palabras."
        "LIVE_MODERATOR_REQUIRED" -> "Esta acción necesita permisos de moderación."
        "LIVE_NOT_ACTIVE", "LIVE_NOT_FOUND" -> "El directo terminó o ya no está disponible."
        "LIVE_AUDIENCE_RATE_LIMITED" -> "Espera un momento antes de participar de nuevo."
        else -> "No se pudo actualizar. Revisa tu conexión y vuelve a intentarlo."
    }
}
