package conectate.com.presentation.social

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.ActiveChallenge
import java.time.Instant
import java.time.temporal.ChronoUnit
import conectate.com.data.model.Challenge
import conectate.com.data.model.ChallengeSubmission
import conectate.com.data.repository.ChallengeRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChallengeViewModel @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val authService: AuthService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private var submissionsJob: Job? = null
    private var participationJob: Job? = null
    private var countdownJob: Job? = null
    private var observeJob: Job? = null

    private val _activeChallenge = MutableStateFlow<ActiveChallenge?>(null)
    val activeChallenge: StateFlow<ActiveChallenge?> = _activeChallenge.asStateFlow()

    private val _challengeSubmissions = MutableStateFlow<List<ChallengeSubmission>>(emptyList())
    val challengeSubmissions: StateFlow<List<ChallengeSubmission>> = _challengeSubmissions.asStateFlow()

    private val _isSubmittingChallenge = MutableStateFlow(false)
    val isSubmittingChallenge: StateFlow<Boolean> = _isSubmittingChallenge.asStateFlow()

    private val _hasParticipated = MutableStateFlow(false)
    val hasParticipated: StateFlow<Boolean> = _hasParticipated.asStateFlow()

    private val _votingCountdown = MutableStateFlow(0)
    val votingCountdown: StateFlow<Int> = _votingCountdown.asStateFlow()

    private val _winnersCountdown = MutableStateFlow(0)
    val winnersCountdown: StateFlow<Int> = _winnersCountdown.asStateFlow()
    
    private val _userChallenges = MutableStateFlow<List<Challenge>>(emptyList())
    val userChallenges: StateFlow<List<Challenge>> = _userChallenges.asStateFlow()

    // Processing votes to prevent double-clicks
    private val processingVotes = mutableSetOf<String>()

    val currentUserId: String?
        get() = authService.getCurrentUserId()

    init {
        observeChallenges()
        observeSession()
    }

    private fun observeSession() {
        viewModelScope.launch {
            authService.observeSession().collect { session ->
                android.util.Log.d("ChallengeViewModel", "Session changed, resetting local state")
                resetLocalParticipationState()
                loadActiveChallenge()
            }
        }
    }



    private fun resetLocalParticipationState() {
        android.util.Log.d("ChallengeViewModel", "Resetting local participation state for new challenge session")
        submissionsJob?.cancel() 
        submissionsJob = null
        
        _hasParticipated.value = false
        _challengeSubmissions.value = emptyList() // Clear old responses immediately
        _isSubmittingChallenge.value = false
        _votingCountdown.value = 0
        _winnersCountdown.value = 0
        processingVotes.clear()
    }

    private fun observeSubmissions(activeChallengeId: String, minCreatedAt: String? = null) {
        android.util.Log.d("ChallengeViewModel", "=== observeSubmissions START for $activeChallengeId (minTime: $minCreatedAt) ===")
        submissionsJob?.cancel()
        submissionsJob = viewModelScope.launch {
            // Ensure we use the Active Row ID (Session) not the Master ID
            val activeId = _activeChallenge.value?.id ?: activeChallengeId
            challengeRepository.observeSubmissions(activeId, minCreatedAt).collect { serverSubs ->
                android.util.Log.d("ChallengeViewModel", "Realtime update received: ${serverSubs.size} submissions")
                
                // Trust server data (Standard Logic)
                // Preserve local vote state while RPC is in flight to avoid flicker
                val updated = serverSubs.map { sub ->
                     if (processingVotes.contains(sub.id)) {
                         val localItem = _challengeSubmissions.value.find { it.id == sub.id }
                         if (localItem != null) {
                             sub.copy(
                                 votedByMe = localItem.votedByMe,
                                 voteCount = localItem.voteCount
                             )
                         } else sub
                     } else {
                         sub
                     }
                }
                
                _challengeSubmissions.value = updated.sortedByDescending { it.voteCount }
                android.util.Log.d("ChallengeViewModel", "Updated submissions list: ${updated.size} items")
            }
        }
    }

    private fun checkParticipation(activeChallengeId: String, minCreatedAt: String? = null) {
        participationJob?.cancel()
        participationJob = viewModelScope.launch {
            android.util.Log.d("ChallengeViewModel", "=== checkParticipation START for $activeChallengeId (minTime: $minCreatedAt) ===")
            challengeRepository.checkParticipation(activeChallengeId, minCreatedAt).onSuccess { participated ->
                android.util.Log.d("ChallengeViewModel", "checkParticipation RESULT: participated=$participated")
                _hasParticipated.value = participated
                if (participated) {
                    android.util.Log.d("ChallengeViewModel", "User HAS participated - activating observer")
                    observeSubmissions(activeChallengeId, minCreatedAt)
                    
                    // FALLBACK: If after 3 seconds we still have empty list, force manual load
                    delay(3000)
                    if (_challengeSubmissions.value.isEmpty()) {
                        android.util.Log.e("ChallengeViewModel", "FALLBACK: Observer didn't populate list after 3s, forcing manual load")
                        loadSubmissions()
                    }
                }
            }
        }
    }
    
    fun loadSubmissions() {
        val challenge = _activeChallenge.value ?: return
        val startTime = getSessionStartTime(challenge)
        viewModelScope.launch {
            android.util.Log.d("ChallengeViewModel", "=== loadSubmissions MANUAL CALL for ${challenge.id} ===")
            challengeRepository.getSubmissions(challenge.id, startTime).onSuccess { // FIXED: Use Instance ID
                android.util.Log.d("ChallengeViewModel", "loadSubmissions SUCCESS: ${it.size} submissions")
                _challengeSubmissions.value = it.sortedByDescending { sub -> sub.voteCount }
            }.onFailure {
                android.util.Log.e("ChallengeViewModel", "loadSubmissions FAILED", it)
            }
        }
    }

    fun submitText(text: String) {
        val challenge = _activeChallenge.value ?: return
        if (_isSubmittingChallenge.value) return
        
        _isSubmittingChallenge.value = true
        viewModelScope.launch {
            challengeRepository.submitText(challenge, text).onSuccess {
                android.widget.Toast.makeText(context, "¡Respuesta enviada!", android.widget.Toast.LENGTH_SHORT).show()
                _hasParticipated.value = true
                
                // CRITICAL FIX: Only activate observer, it will load and emit submissions automatically
                observeSubmissions(challenge.id, getSessionStartTime(challenge)) // FIXED: Use Session ID
                _isSubmittingChallenge.value = false
            }.onFailure {
                android.widget.Toast.makeText(context, "Error: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                _isSubmittingChallenge.value = false
            }
        }
    }

    fun submitMedia(uri: android.net.Uri, mimeType: String, extension: String) {
        val challenge = _activeChallenge.value ?: return
        if (_isSubmittingChallenge.value) return

        _isSubmittingChallenge.value = true
        viewModelScope.launch {
            challengeRepository.submitMedia(challenge, uri, mimeType, extension).onSuccess {
                android.widget.Toast.makeText(context, "¡Reto multimedia cumplido!", android.widget.Toast.LENGTH_SHORT).show()
                _hasParticipated.value = true
                
                // CRITICAL FIX: Only activate observer, it will load and emit submissions automatically
                observeSubmissions(challenge.id, getSessionStartTime(challenge)) // FIXED: Use Session ID
                _isSubmittingChallenge.value = false
            }.onFailure {
                android.widget.Toast.makeText(context, "Error: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                _isSubmittingChallenge.value = false
            }
        }
    }
    
    // Supporting the specific Audio recorder file submission if needed
    fun submitAudio(file: java.io.File) {
        val challenge = _activeChallenge.value ?: return
        if (_isSubmittingChallenge.value) return

        _isSubmittingChallenge.value = true
        viewModelScope.launch {
            challengeRepository.submitMedia(challenge, android.net.Uri.fromFile(file), "audio/mp4", "mp4").onSuccess {
                android.widget.Toast.makeText(context, "¡Audio enviado!", android.widget.Toast.LENGTH_SHORT).show()
                _hasParticipated.value = true
                
                // CRITICAL FIX: Only activate observer, it will load and emit submissions automatically
                observeSubmissions(challenge.id, getSessionStartTime(challenge)) // FIXED: Use Session ID
                _isSubmittingChallenge.value = false
            }.onFailure {
                android.widget.Toast.makeText(context, "Error: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                _isSubmittingChallenge.value = false
            }
        }
    }

    fun submitDrawing(lines: List<conectate.com.presentation.components.Line>) {
        val challenge = _activeChallenge.value ?: return
        if (_isSubmittingChallenge.value) return

        _isSubmittingChallenge.value = true
        viewModelScope.launch {
            challengeRepository.submitDrawing(challenge, lines).onSuccess {
                android.widget.Toast.makeText(context, "¡Reto cumplido!", android.widget.Toast.LENGTH_SHORT).show()
                _hasParticipated.value = true
                
                // CRITICAL FIX: Only activate observer, it will load and emit submissions automatically
                observeSubmissions(challenge.id, getSessionStartTime(challenge)) // FIXED: Use Session ID
                _isSubmittingChallenge.value = false
            }.onFailure {
                android.widget.Toast.makeText(context, "Error: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                _isSubmittingChallenge.value = false
            }
        }
    }

    fun toggleChallengeVote(submissionId: String) {
        val challenge = _activeChallenge.value ?: return
        
        // Permite votar si el reto está activo o en fase de votación
        if (challenge.status == "completed") {
            android.widget.Toast.makeText(context, "El reto ya ha finalizado", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        if (processingVotes.contains(submissionId)) return
        
        val currentSubmissions = _challengeSubmissions.value
        val currentItem = currentSubmissions.find { it.id == submissionId } ?: return
        
        // Optimistic UI update
        val isAddingVote = !currentItem.votedByMe
        val optimisticCount = if (isAddingVote) currentItem.voteCount + 1 else maxOf(0, currentItem.voteCount - 1)
        
        processingVotes.add(submissionId)
        
        // Update local state immediately for snappy UI
        _challengeSubmissions.value = currentSubmissions.map {
            if (it.id == submissionId) {
                it.copy(voteCount = optimisticCount, votedByMe = isAddingVote)
            } else it
        }.sortedByDescending { it.voteCount }
        
        viewModelScope.launch {
            challengeRepository.toggleVote(challenge.id, submissionId).onSuccess { (actualCount, finalVotedByMe) ->
                // Update with server confirmed values
                _challengeSubmissions.value = _challengeSubmissions.value.map {
                    if (it.id == submissionId) {
                        it.copy(voteCount = actualCount, votedByMe = finalVotedByMe)
                    } else it
                }.sortedByDescending { it.voteCount }
                
                // We REMOVED loadSubmissions() here because observeSubmissions (Realtime) 
                // will handle the global state update.
            }.onFailure { error ->
                android.util.Log.e("ChallengeViewModel", "Vote toggle FAILED for $submissionId", error)
                android.widget.Toast.makeText(context, "Error al votar: ${error.message}", android.widget.Toast.LENGTH_SHORT).show()
                loadSubmissions() // Rollback only on error
            }
            
            // Wait slightly before allowing another click to ensure UI stability and prevent flickering (Optimistic UI)
            delay(3000) 
            processingVotes.remove(submissionId)
        }
    }
    
    fun loadUserChallenges() {
        viewModelScope.launch {
            challengeRepository.getUserChallenges().onSuccess {
                _userChallenges.value = it
            }
        }
    }

    fun deleteUserChallenge(challengeId: String) {
        viewModelScope.launch {
            challengeRepository.deleteChallengeMaster(challengeId).onSuccess {
                android.widget.Toast.makeText(context, "Reto eliminado", android.widget.Toast.LENGTH_SHORT).show()
                loadUserChallenges()
            }.onFailure {
                android.widget.Toast.makeText(context, "Error al eliminar: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun passTorch(friendId: String) {
        val challenge = _activeChallenge.value ?: return
        viewModelScope.launch {
            challengeRepository.passTorch(challenge.challenge.title, friendId).onSuccess {
                android.widget.Toast.makeText(context, "¡Antorcha pasada con éxito!", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "Error al pasar la antorcha", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun submitChallengeMediaFromUri(uri: android.net.Uri) {
        val challenge = _activeChallenge.value ?: return
        viewModelScope.launch {
            _isSubmittingChallenge.value = true
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"

                challengeRepository.submitMedia(challenge, uri, mimeType, extension).onSuccess {
                    android.widget.Toast.makeText(context, "¡Reto multimedia cumplido!", android.widget.Toast.LENGTH_SHORT).show()
                    _hasParticipated.value = true
                    
                    // FIXED: Reload submissions immediately to populate the list
                    observeSubmissions(challenge.id, getSessionStartTime(challenge))
                    
                    _isSubmittingChallenge.value = false
                }.onFailure {
                    android.widget.Toast.makeText(context, "Error: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                    _isSubmittingChallenge.value = false
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Error leyendo archivo", android.widget.Toast.LENGTH_SHORT).show()
                _isSubmittingChallenge.value = false
            }
        }
    }

    // Creating a challenge (for the dialog)
    fun createChallenge(title: String, description: String, type: String, difficulty: String = "medium") {
        viewModelScope.launch {
             challengeRepository.createChallengeMaster(title, description, type, difficulty).onSuccess {
                 android.widget.Toast.makeText(context, "¡Reto creado con éxito!", android.widget.Toast.LENGTH_SHORT).show()
                 loadUserChallenges()
             }.onFailure {
                 android.widget.Toast.makeText(context, "Error al crear el reto", android.widget.Toast.LENGTH_SHORT).show()
             }
        }
    }

    private val _activeCountdown = MutableStateFlow(0)
    val activeCountdown: StateFlow<Int> = _activeCountdown.asStateFlow()

    private fun observeChallenges() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            // Cargar inicial
            challengeRepository.getActiveChallenge().onSuccess { initial ->
                _activeChallenge.value = initial
                resetLocalParticipationState() 
                checkParticipation(initial.challengeId, getSessionStartTime(initial)) 
                startMasterTimer(initial)
            }.onFailure {
                android.util.Log.e("ChallengeViewModel", "Initial active challenge load failed", it)
            }

            // Escuchar cambios en tiempo real
            challengeRepository.observeActiveChallenge().collect { updated ->
                val previous = _activeChallenge.value
                _activeChallenge.value = updated
                
                if (previous?.id != updated.id) {
                    resetLocalParticipationState()
                    checkParticipation(updated.challengeId, getSessionStartTime(updated))
                }
                
                // Si el estado cambia a voting o completed, forzar recarga de participaciones
                // para que el podio o la lista de votos se vea correctamente incluso si el
                // usuario no participó antes o si el observer no ha emitido nada aún.
                if (updated.status == "completed" || updated.status == "voting") {
                    if (_challengeSubmissions.value.isEmpty()) {
                        android.util.Log.d("ChallengeViewModel", "Forcing submissions load for state: ${updated.status}")
                        loadSubmissions()
                    }
                }
                
                if (previous?.status != updated.status || previous?.id != updated.id) {
                    startMasterTimer(updated)
                }
            }
        }
    }

    private fun startMasterTimer(challenge: ActiveChallenge) {
        countdownJob?.cancel()
        
        val expiryStr = when (challenge.status) {
            "voting" -> challenge.votingExpiresAt ?: challenge.expiresAt
            else -> challenge.expiresAt
        }
        
        val seconds = try {
            val expiry = java.time.Instant.parse(expiryStr)
            val now = java.time.Instant.now()
            java.time.Duration.between(now, expiry).seconds.toInt().coerceAtLeast(0)
        } catch (e: Exception) {
            0
        }

        // Actualizar el Flow correspondiente según el estado
        _votingCountdown.value = if (challenge.status == "voting") seconds else 0
        _winnersCountdown.value = if (challenge.status == "completed") seconds else 0
        _activeCountdown.value = if (challenge.status == "active") seconds else 0

        countdownJob = viewModelScope.launch {
            var currentSeconds = seconds
            while (currentSeconds > 0) {
                delay(1000)
                currentSeconds--
                when (challenge.status) {
                    "voting" -> _votingCountdown.value = currentSeconds
                    "completed" -> _winnersCountdown.value = currentSeconds
                    "active" -> _activeCountdown.value = currentSeconds
                }
            }
            
            // ¡TIEMPO AGOTADO! 
            android.util.Log.d("ChallengeViewModel", "Phase ${challenge.status} reached 0. Insisting...")
            
            // Polling agresivo: Pregunta cada 1 segundo por 10 segundos
            repeat(10) {
                if (_activeChallenge.value?.status != challenge.status) return@launch
                loadActiveChallenge()
                delay(1000)
            }
        }
    }

    fun loadActiveChallenge() {
        viewModelScope.launch {
            challengeRepository.getActiveChallenge().onSuccess { updated ->
                val previous = _activeChallenge.value
                _activeChallenge.value = updated
                
                if (previous?.id != updated.id) {
                    resetLocalParticipationState()
                    checkParticipation(updated.challengeId, getSessionStartTime(updated)) 
                }

                if (previous?.status != updated.status || previous?.id != updated.id || countdownJob?.isActive != true) {
                    startMasterTimer(updated)
                }
            }.onFailure {
                android.util.Log.e("ChallengeViewModel", "Manual active challenge load failed", it)
            }
        }
    }

    private fun getSessionStartTime(challenge: ActiveChallenge): String? {
        return try {
            val expires = Instant.parse(challenge.expiresAt)
            val start = expires.minus(16, ChronoUnit.MINUTES)
            start.toString()
        } catch (e: Exception) {
            android.util.Log.e("ChallengeViewModel", "Error parsing time", e)
            null
        }
    }

    fun refreshChallengeData() {
        android.util.Log.d("ChallengeViewModel", "Manual Refresh Triggered")
        loadActiveChallenge()
        // If we have an active challenge and participated, force reload submissions
        if (_hasParticipated.value && _activeChallenge.value != null) {
            loadSubmissions()
        }
    }
}
