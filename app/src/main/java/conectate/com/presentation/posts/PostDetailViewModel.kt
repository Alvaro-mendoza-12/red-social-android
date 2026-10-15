package conectate.com.presentation.posts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Post
import conectate.com.data.model.Comment
import conectate.com.data.model.PostThreadSummary
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.UserRepository
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

data class PostDetailUiState(
    val post: Post? = null,
    val comments: List<Comment> = emptyList(),
    val isLoadingPost: Boolean = false,
    val isLoadingComments: Boolean = true,
    val threadSummary: PostThreadSummary? = null,
    val isSummarizingThread: Boolean = false,
    val threadSummaryError: String? = null,
    val error: String? = null
)

@HiltViewModel
class PostDetailViewModel @Inject constructor(
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
    private val authService: conectate.com.data.supabase.AuthService,
    private val shareRepository: conectate.com.data.repository.ShareRepository,
    private val voiceMessageManager: conectate.com.presentation.messages.VoiceMessageManager,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostDetailUiState())
    val uiState: StateFlow<PostDetailUiState> = _uiState.asStateFlow()
    private val reactionLoader = PostReactionUsersLoader(viewModelScope,
        { authService.getCurrentUserId() }, postRepository::getPostReactions)
    internal val reactionUsersState = reactionLoader.state
    fun loadPostReactions(postId: String, type: String? = null) = reactionLoader.load(postId, type)
    fun clearPostReactions() = reactionLoader.clear()

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

    fun resolvePublicMention(handle: String, onResolved: (String?) -> Unit) {
        viewModelScope.launch { onResolved(userRepository.resolvePublicMention(handle)?.id) }
    }

    val currentUserId: String?
        get() = authService.getCurrentUserId()

    fun loadPost(postId: String) {
        val currentState = _uiState.value
        if (currentState.post?.id == postId && !currentState.isLoadingPost) {
            loadJob?.cancel()
            loadJob = viewModelScope.launch {
                refreshPostSnapshot(postId)
                loadComments(postId)
            }
            return
        }

        loadJob?.cancel()
        commentsJob?.cancel()

        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingPost = true,
                threadSummary = null,
                isSummarizingThread = false,
                threadSummaryError = null,
                error = null
            )
            
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
                    error = it.toUserFacingMessage(
                        "Esta publicación ya no está disponible."
                    )
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
        val post = _uiState.value.post?.takeIf { it.id == postId } ?: return
        setReaction(postId, if (post.myReaction != null || post.likedByMe) null else "LIKE")
    }

    fun setReaction(postId: String, requested: String?) {
        val previous = _uiState.value.post?.takeIf { it.id == postId } ?: return
        val account = authService.getCurrentUserId() ?: return
        val normalized = requested?.uppercase()?.takeIf { it in setOf("LIKE", "LOVE", "HAHA", "WOW", "SAD", "ANGRY", "CARE", "FIRE") }
        if (requested != null && normalized == null) return
        val old = previous.myReaction ?: if (previous.likedByMe) "LIKE" else null
        val selected = normalized?.takeUnless { it == old }
        if (!likeRequestsInFlight.add(postId)) return
        _uiState.update { state -> state.copy(post = state.post?.takeIf { it.id == postId }?.withReaction(selected) ?: state.post) }
        viewModelScope.launch {
            try {
                postRepository.setPostReaction(postId, selected, old != null).getOrThrow()
                // Server totals include reactions from other people and survive app refreshes.
                val fresh = postRepository.getPostById(postId).getOrNull()
                if (account == authService.getCurrentUserId() && _uiState.value.post?.id == postId && fresh != null)
                    _uiState.update { it.copy(post = fresh) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (account == authService.getCurrentUserId() && _uiState.value.post?.id == postId) {
                    _uiState.update { state -> state.copy(post = state.post?.copy(likedByMe = previous.likedByMe,
                        myReaction = previous.myReaction, likeCount = previous.likeCount, reactionCounts = previous.reactionCounts)) }
                    conectate.com.presentation.common.AppFeedbackBus.show("No se pudo guardar la reacción. Reintenta.")
                }
            } finally { likeRequestsInFlight.remove(postId) }
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
                val newShareCount = if (wasShared) maxOf(0, post.shareCount - 1) else post.shareCount + 1

                _uiState.value = state.copy(
                    post = post.copy(sharedByMe = !wasShared, shareCount = newShareCount)
                )

                val result = if (wasShared) {
                    shareRepository.unshareContent(userId, "post", postId)
                } else {
                    shareRepository.shareContent(userId, "post", postId)
                }

                if (result.isFailure) {
                    _uiState.value = state.copy(post = post)
                }
            } catch (e: Exception) {
                _uiState.value = state.copy(post = post)
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
                .onSuccess { newComment ->
                    val current = _uiState.value
                    val currentPost = current.post
                    val nextComments = current.comments
                        .filterNot { it.id == newComment.id }
                        .plus(newComment)
                    _uiState.value = current.copy(
                        comments = nextComments,
                        post = currentPost?.copy(commentCount = currentPost.commentCount + 1),
                        isLoadingComments = false,
                        threadSummary = null,
                        threadSummaryError = null
                    )
                    loadComments(postId)
                    refreshPostSnapshot(postId)
                }
                .onFailure {
                    android.widget.Toast.makeText(
                        context,
                        it.message ?: "No se pudo enviar el comentario",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    fun addStickerComment(postId: String, stickerUrl: String, parentId: String? = null) {
        viewModelScope.launch {
            postRepository.addStickerComment(postId, stickerUrl, parentId)
                .onSuccess { newComment ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        comments = current.comments.filterNot { it.id == newComment.id } + newComment,
                        post = current.post?.copy(commentCount = current.post.commentCount + 1),
                        isLoadingComments = false,
                        threadSummary = null,
                        threadSummaryError = null
                    )
                    loadComments(postId)
                    refreshPostSnapshot(postId)
                }
                .onFailure {
                    android.widget.Toast.makeText(
                        context,
                        it.message ?: "No se pudo enviar el sticker",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
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
                    val current = _uiState.value
                    val currentPost = current.post
                    val removedCount = current.comments.count { it.id == commentId || it.parentId == commentId }
                    _uiState.value = current.copy(
                        comments = current.comments.filterNot { it.id == commentId || it.parentId == commentId },
                        post = currentPost?.copy(
                            commentCount = maxOf(0, currentPost.commentCount - removedCount.coerceAtLeast(1))
                        ),
                        threadSummary = null,
                        threadSummaryError = null
                    )
                    loadComments(postId)
                    refreshPostSnapshot(postId)
                }
                .onFailure {
                    android.util.Log.e("PostDetailViewModel", "Error deleting comment", it)
                    android.widget.Toast.makeText(context, "Error al eliminar: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun editComment(commentId: String, newContent: String) {
        val postId = _uiState.value.post?.id ?: return
        viewModelScope.launch {
            postRepository.updateCommentContent(commentId, newContent)
                .onSuccess {
                    android.util.Log.d("PostDetailViewModel", "Comment updated successfully")
                    android.widget.Toast.makeText(context, "Comentario editado", android.widget.Toast.LENGTH_SHORT).show()
                    _uiState.value = _uiState.value.copy(
                        comments = _uiState.value.comments.map {
                            if (it.id == commentId) it.copy(content = newContent) else it
                        },
                        threadSummary = null,
                        threadSummaryError = null
                    )
                    loadComments(postId)
                }
                .onFailure {
                    android.util.Log.e("PostDetailViewModel", "Error editing comment", it)
                    android.widget.Toast.makeText(context, "Error al editar: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun toggleCommentReaction(comment: Comment, reactionType: String) {
        val normalized = reactionType.uppercase().takeIf { it == "LIKE" || it == "DISLIKE" } ?: return
        val previous = comment.myReaction
        val next = if (previous == normalized) null else normalized
        fun updatedComment(source: Comment): Comment {
            val counts = source.reactionCounts.toMutableMap()
            previous?.let { counts[it] = maxOf(0, (counts[it] ?: 0) - 1) }
            next?.let { counts[it] = (counts[it] ?: 0) + 1 }
            return source.copy(reactionCounts = counts, myReaction = next)
        }
        _uiState.value = _uiState.value.copy(
            comments = _uiState.value.comments.map { if (it.id == comment.id) updatedComment(it) else it }
        )
        viewModelScope.launch {
            postRepository.setCommentReaction(comment.id, next).onFailure {
                _uiState.value = _uiState.value.copy(
                    comments = _uiState.value.comments.map { if (it.id == comment.id) comment else it }
                )
                android.widget.Toast.makeText(context, "No se pudo guardar la reacción", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun requestThreadSummary(postId: String) {
        val textCommentCount = _uiState.value.comments.count { it.content.trim().length >= 3 }
        if (textCommentCount < 3) {
            _uiState.value = _uiState.value.copy(
                threadSummary = null,
                isSummarizingThread = false,
                threadSummaryError = "Se necesitan al menos tres comentarios con texto para preparar un resumen."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSummarizingThread = true,
                threadSummaryError = null
            )
            postRepository.summarizePostThread(postId)
                .onSuccess { summary ->
                    _uiState.value = _uiState.value.copy(
                        threadSummary = summary,
                        isSummarizingThread = false,
                        threadSummaryError = null
                    )
                }
                .onFailure { error ->
                    val raw = error.message.orEmpty()
                    val message = when {
                        raw.contains("THREAD_SUMMARY_NOT_ENOUGH_CONTENT", ignoreCase = true) ->
                            "Se necesitan al menos tres comentarios con texto para preparar un resumen."
                        raw.contains("THREAD_SUMMARY_RATE_LIMITED", ignoreCase = true) ->
                            "Espera un momento antes de solicitar otro resumen."
                        raw.contains("THREAD_SUMMARY_UNAVAILABLE", ignoreCase = true) ->
                            "El resumen no está disponible en este momento. Inténtalo más tarde."
                        else -> error.toUserFacingMessage(
                            "No se pudo preparar el resumen. Inténtalo nuevamente."
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        threadSummary = null,
                        isSummarizingThread = false,
                        threadSummaryError = message
                    )
                }
        }
    }

    fun reportPost(post: Post, reason: String, details: String?, evidenceUri: android.net.Uri?) {
        viewModelScope.launch {
            val result = postRepository.submitContentReport(
                contentType = "post",
                contentId = post.id,
                reportedUserId = post.userId,
                reportedUserName = post.userName,
                reason = reason,
                details = details,
                evidenceUri = evidenceUri
            )
            showReportResult(result)
        }
    }

    fun reportComment(comment: Comment, reason: String, details: String?, evidenceUri: android.net.Uri?) {
        viewModelScope.launch {
            val result = postRepository.submitContentReport(
                contentType = "comment",
                contentId = comment.id,
                reportedUserId = comment.userId,
                reportedUserName = comment.userName ?: "Usuario",
                reason = reason,
                details = details,
                evidenceUri = evidenceUri
            )
            showReportResult(result)
        }
    }

    private suspend fun refreshPostSnapshot(postId: String) {
        postRepository.getPostById(postId).onSuccess { freshPost ->
            _uiState.value = _uiState.value.copy(post = freshPost)
        }
    }

    private fun showReportResult(result: Result<Unit>) {
        if (result.isSuccess) {
            android.widget.Toast.makeText(context, "Reporte enviado", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(
                context,
                result.exceptionOrNull()?.message ?: "No se pudo enviar el reporte",
                android.widget.Toast.LENGTH_LONG
            ).show()
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
                        _uiState.value.post?.let { currentPost ->
                            _uiState.value = _uiState.value.copy(
                                post = currentPost.copy(commentCount = currentPost.commentCount + 1)
                            )
                        }
                        loadComments(postId)
                        refreshPostSnapshot(postId)
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
