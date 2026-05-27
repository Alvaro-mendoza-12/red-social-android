package conectate.com.presentation.posts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Post
import conectate.com.data.model.Comment
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

data class PostDetailUiState(
    val post: Post? = null,
    val comments: List<Comment> = emptyList(),
    val isLoadingPost: Boolean = false,
    val isLoadingComments: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class PostDetailViewModel @Inject constructor(
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
    private val authService: conectate.com.data.supabase.AuthService,
    private val shareRepository: conectate.com.data.repository.ShareRepository,
    private val voiceMessageManager: conectate.com.presentation.messages.VoiceMessageManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    init {
        voiceMessageManager.setScope(viewModelScope)
    }

    private val _uiState = MutableStateFlow(PostDetailUiState())
    val uiState: StateFlow<PostDetailUiState> = _uiState.asStateFlow()

    // Voice Manager State Expose
    val voiceState = voiceMessageManager.state
    val recordingDuration = voiceMessageManager.duration
    val voicePlaybackProgress = voiceMessageManager.playbackProgress
    val voicePlaybackDurationLabel = voiceMessageManager.playbackDurationLabel

    private var loadJob: Job? = null
    private var commentsJob: Job? = null
    private var mentionSearchJob: Job? = null
    private val likeRequestsInFlight = mutableSetOf<String>()
    private val shareRequestsInFlight = mutableSetOf<String>()

    private val _mentionSuggestions = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val mentionSuggestions: StateFlow<List<conectate.com.data.model.User>> = _mentionSuggestions.asStateFlow()

    val currentUserId: String?
        get() = authService.getCurrentUserId()

    fun loadPost(postId: String) {
        val currentState = _uiState.value
        if (currentState.post?.id == postId && !currentState.isLoadingPost) {
           // Already loaded, maybe just refresh comments?
           loadComments(postId)
           return
        }

        loadJob?.cancel()
        commentsJob?.cancel()

        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPost = true, error = null)
            
            // 1. Load Post
            val postResult = postRepository.getPostById(postId)
            postResult.onSuccess { post ->
                _uiState.value = _uiState.value.copy(
                    post = post,
                    isLoadingPost = false
                )
                // 2. Load Comments independently
                loadComments(postId)
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isLoadingPost = false,
                    error = it.message ?: "Error al cargar la publicación"
                )
            }
        }
    }

    private fun loadComments(postId: String) {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            postRepository.getPostComments(postId).collect { comments ->
                _uiState.value = _uiState.value.copy(
                    comments = comments,
                    isLoadingComments = false
                )
            }
        }
    }

    fun toggleLike(postId: String) {
        val state = _uiState.value
        val post = state.post ?: return
        if (!likeRequestsInFlight.add(postId)) return
        
        viewModelScope.launch {
            try {
                val wasLiked = post.likedByMe
                val newLikeCount = if (wasLiked) maxOf(0, post.likeCount - 1) else post.likeCount + 1

                _uiState.value = state.copy(post = post.copy(likedByMe = !wasLiked, likeCount = newLikeCount))

                val result = postRepository.toggleLike(postId, wasLiked)
                if (result.isFailure) {
                    _uiState.value = state.copy(post = post)
                }
            } finally {
                likeRequestsInFlight.remove(postId)
            }
        }
    }

    fun toggleShare(postId: String) {
        val state = _uiState.value
        val post = state.post ?: return
        if (!shareRequestsInFlight.add(postId)) return
        
        viewModelScope.launch {
            try {
                val userId = authService.getCurrentUserId() ?: return@launch
                val wasShared = post.sharedByMe

                _uiState.value = state.copy(post = post.copy(sharedByMe = !wasShared))

                val result = if (wasShared) {
                    shareRepository.unshareContent(userId, "post", postId)
                } else {
                    shareRepository.shareContent(userId, "post", postId)
                }

                if (result.isFailure) {
                    _uiState.value = state.copy(post = post)
                }
            } finally {
                shareRequestsInFlight.remove(postId)
            }
        }
    }

    fun onCommentDraftChanged(text: String) {
        val query = extractActiveMentionQuery(text)
        if (query.isNullOrBlank()) {
            _mentionSuggestions.value = emptyList()
            mentionSearchJob?.cancel()
            return
        }

        mentionSearchJob?.cancel()
        mentionSearchJob = viewModelScope.launch {
            userRepository.searchMentionCandidates(
                query = query,
                excludeUserId = authService.getCurrentUserId(),
                limit = 6
            ).onSuccess { users ->
                _mentionSuggestions.value = users
            }.onFailure {
                _mentionSuggestions.value = emptyList()
            }
        }
    }

    fun clearMentionSuggestions() {
        _mentionSuggestions.value = emptyList()
    }

    fun addComment(postId: String, content: String, parentId: String? = null) {
        viewModelScope.launch {
            postRepository.addComment(postId, content, parentId)
                .onSuccess {
                    // Force reload comments and post to show the new one and update counter
                    loadComments(postId)
                    loadPost(postId)
                }
        }
    }

    fun deleteComment(commentId: String) {
        val postId = _uiState.value.post?.id ?: return
        viewModelScope.launch {
            postRepository.deleteComment(commentId)
                .onSuccess {
                    android.util.Log.d("PostDetailViewModel", "Comment deleted successfully")
                    android.widget.Toast.makeText(context, "Comentario eliminado", android.widget.Toast.LENGTH_SHORT).show()
                    // Force reload comments to update UI
                    loadComments(postId)
                }
                .onFailure {
                    android.util.Log.e("PostDetailViewModel", "Error deleting comment", it)
                    android.widget.Toast.makeText(context, "Error al eliminar: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
        }
    }
    
    // Voice Message Recording - delegated to VoiceMessageManager
    fun startRecording() = voiceMessageManager.startRecording()
    fun pauseRecording() = voiceMessageManager.pauseRecording()
    fun resumeRecording() = voiceMessageManager.resumeRecording()
    fun resumeRecordingFromReview() = voiceMessageManager.resumeRecordingFromReview()
    fun cancelRecording() = voiceMessageManager.cancelRecording()
    
    fun stopRecording(sendDirectly: Boolean) {
        if (sendDirectly) {
            voiceMessageManager.stopRecording()
            sendCurrentRecording()
        } else {
            voiceMessageManager.stopRecording()
        }
    }
    
    fun sendCurrentRecording() {
        val postId = _uiState.value.post?.id ?: return
        viewModelScope.launch {
            val file = voiceMessageManager.getRecordedFile()
            if (file != null && file.exists()) {
                postRepository.addAudioComment(postId, file)
                    .onSuccess {
                        voiceMessageManager.clearRecording()
                        loadComments(postId)
                        loadPost(postId)
                    }
            }
        }
    }
    
    // Preview playback controls
    fun playDraftPreview() = voiceMessageManager.playPreview()
    fun pauseDraftPreview() = voiceMessageManager.pausePreview()
    fun seekDraftPreview(progress: Float) = voiceMessageManager.seekPreview(progress)
    fun voiceMessageManagerIsPreviewPlaying(): Boolean = voiceMessageManager.isPreviewPlaying()

    private fun extractActiveMentionQuery(text: String): String? {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0 || atIndex == text.lastIndex) return null

        val mentionCandidate = text.substring(atIndex + 1)
        if (mentionCandidate.any { it.isWhitespace() }) return null
        if (mentionCandidate.length > 30) return null

        return mentionCandidate.trim()
    }
}
