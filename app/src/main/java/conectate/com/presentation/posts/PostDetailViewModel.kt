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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

data class PostDetailUiState(
    val post: Post? = null,
    val comments: List<Comment> = emptyList(),
    val isLoadingPost: Boolean = false,
    val isLoadingComments: Boolean = true,
    val commentsError: String? = null,
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
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostDetailUiState())
    val uiState: StateFlow<PostDetailUiState> = _uiState.asStateFlow()
    private val reactionWriter = PostReactionWriter(
        viewModelScope, authService::getCurrentUserId,
        { id -> _uiState.value.post?.takeIf { it.id == id } },
        postRepository::setPostReaction, postRepository::getPostById,
        { post -> _uiState.update { state -> state.copy(post = state.post?.let { current ->
            if (current.id == post.id) current.copyReactionFrom(post) else current
        }) } },
        { conectate.com.presentation.common.AppFeedbackBus.show("No se pudo guardar la reacción. Reintenta.") },
    )
    private val reactionLoader = PostReactionUsersLoader(viewModelScope,
        { authService.getCurrentUserId() }, postRepository::getPostReactions)
    internal val reactionUsersState = reactionLoader.state
    fun loadPostReactions(postId: String, type: String? = null) = reactionLoader.load(postId, type)
    fun clearPostReactions() = reactionLoader.clear()

    private var loadJob: Job? = null
    private var commentsJob: Job? = null
    private var mentionSearchJob: Job? = null
    private var summaryJob: Job? = null
    private val shareRequestsInFlight = mutableSetOf<String>()
    private var selectedPostId: String? = null
    private var postReadEpoch = 0L
    private var activeAccountId = authService.getCurrentUserId()
    private val _commentPosting = MutableStateFlow(false)
    val commentPosting: StateFlow<Boolean> = _commentPosting.asStateFlow()
    private var commentPostRevision = 0L
    private val _commentEditing = MutableStateFlow(false)
    val commentEditing = _commentEditing.asStateFlow()
    private var commentEditRevision = 0L
    private val commentReactionsInFlight = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            postRepository.reactionChanges.collect { change ->
                if (change.actorId == authService.getCurrentUserId()) reactionWriter.acceptConfirmed(change.postId, change.reaction)
            }
        }
        viewModelScope.launch {
            shareRepository.postChanges.collect { change ->
                if (change.actorId == authService.getCurrentUserId()) _uiState.update { state ->
                    state.copy(post = state.post?.let(change::applyTo))
                }
            }
        }
        viewModelScope.launch {
            authService.currentSession.map { authService.getCurrentUserId() }.distinctUntilChanged().collect { account ->
                if (account != activeAccountId) {
                    val target = selectedPostId
                    activeAccountId = account
                    postReadEpoch++
                    selectedPostId = null
                    reactionWriter.clear()
                    reactionLoader.clear()
                    loadJob?.cancel()
                    commentsJob?.cancel()
                    clearMentionSuggestions()
                    invalidateThreadSummary()
                    _uiState.value = PostDetailUiState()
                    _commentPosting.value = false
                    commentPostRevision++
                    _commentEditing.value = false
                    commentEditRevision++
                    commentReactionsInFlight.clear()
                    if (account != null && target != null) loadPost(target)
                }
            }
        }
    }

    private val _mentionSuggestions = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val mentionSuggestions: StateFlow<List<conectate.com.data.model.User>> = _mentionSuggestions.asStateFlow()

    fun resolvePublicMention(handle: String, onResolved: (String?) -> Unit) {
        val postId = selectedPostId ?: return
        val account = authService.getCurrentUserId()
        val epoch = postReadEpoch
        viewModelScope.launch {
            val result = conectate.com.core.network.runReadWithDeadline(10_000) { userRepository.resolvePublicMention(handle)?.id }
            currentCoroutineContext().ensureActive()
            if (isCurrentPost(postId, account, epoch)) onResolved(result.getOrNull())
        }
    }

    val currentUserId: String?
        get() = authService.getCurrentUserId()

    fun loadPost(postId: String) {
        invalidateThreadSummary()
        val currentState = _uiState.value
        if (selectedPostId != postId) {
            clearMentionSuggestions()
            _commentPosting.value = false
            commentPostRevision++
            _commentEditing.value = false
            commentEditRevision++
        }
        selectedPostId = postId
        val epoch = ++postReadEpoch
        val account = authService.getCurrentUserId()
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
            val reactionReadVersion = reactionWriter.readVersion()
            _uiState.value = _uiState.value.copy(
                post = _uiState.value.post?.takeIf { it.id == postId },
                comments = if (currentState.post?.id == postId) currentState.comments else emptyList(),
                isLoadingPost = true,
                isLoadingComments = true,
                commentsError = null,
                threadSummary = null,
                isSummarizingThread = false,
                threadSummaryError = null,
                error = null
            )
            
            // 1. Load Post
            val postResult = postRepository.getPostById(postId)
            postResult.onSuccess { post ->
                if (!isCurrentPost(postId, account, epoch)) return@onSuccess
                _uiState.value = _uiState.value.copy(
                    post = reactionWriter.reconcile(post, reactionReadVersion),
                    isLoadingPost = false
                )
                // 2. Load Comments independently
                loadComments(postId)
            }.onFailure {
                if (!isCurrentPost(postId, account, epoch)) return@onFailure
                _uiState.value = _uiState.value.copy(
                    isLoadingPost = false,
                    isLoadingComments = false,
                    error = it.toUserFacingMessage(
                        "Esta publicación ya no está disponible."
                    )
                )
            }
        }
    }

    private fun loadComments(postId: String) {
        commentsJob?.cancel()
        val account = authService.getCurrentUserId()
        val epoch = postReadEpoch
        commentsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingComments = true, commentsError = null)
            try {
                val comments = readPostComments { postRepository.getPostComments(postId) }.getOrThrow()
                if (isCurrentPost(postId, account, epoch)) _uiState.value = _uiState.value.copy(
                    comments = comments, isLoadingComments = false, commentsError = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (isCurrentPost(postId, account, epoch)) _uiState.value = _uiState.value.copy(
                    isLoadingComments = false,
                    commentsError = "No pudimos cargar los comentarios. Inténtalo de nuevo.",
                )
            }
        }
    }

    fun retryComments() { selectedPostId?.let(::loadComments) }

    fun closePost(postId: String) {
        if (selectedPostId != postId) return
        selectedPostId = null
        postReadEpoch++
        commentPostRevision++
        commentEditRevision++
        loadJob?.cancel()
        commentsJob?.cancel()
        clearMentionSuggestions()
        invalidateThreadSummary()
        _commentPosting.value = false
        _commentEditing.value = false
        commentReactionsInFlight.clear()
        _uiState.value = PostDetailUiState()
    }

    fun toggleLike(postId: String) {
        val post = _uiState.value.post?.takeIf { it.id == postId } ?: return
        setReaction(postId, if (post.myReaction != null || post.likedByMe) null else "LIKE")
    }

    fun setReaction(postId: String, requested: String?) {
        reactionWriter.set(postId, requested)
    }

    fun toggleShare(postId: String) {
        val post = _uiState.value.post?.takeIf { it.id == postId } ?: return
        val account = authService.getCurrentUserId() ?: return
        if (!shareRequestsInFlight.add(postId)) return
        viewModelScope.launch {
            try {
                val result = if (post.sharedByMe) shareRepository.unshareContent(account, "post", postId)
                    else shareRepository.shareContent(account, "post", postId)
                if (account != authService.getCurrentUserId()) return@launch
                conectate.com.presentation.common.AppFeedbackBus.show(
                    if (result.isSuccess) { if (post.sharedByMe) "Quitado de tu perfil" else "Añadido a tu perfil" }
                    else "No se pudo guardar el cambio. Vuelve a intentarlo.")
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { shareRequestsInFlight.remove(postId) }
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
        val postId = selectedPostId ?: return
        val account = authService.getCurrentUserId()
        val epoch = postReadEpoch
        mentionSearchJob = viewModelScope.launch {
            val result = userRepository.searchMentionCandidates(
                query = query,
                excludeUserId = authService.getCurrentUserId(),
                limit = 6
            )
            currentCoroutineContext().ensureActive()
            if (!isCurrentPost(postId, account, epoch)) return@launch
            result.onSuccess { users ->
                _mentionSuggestions.value = users
            }.onFailure {
                _mentionSuggestions.value = emptyList()
            }
        }
    }

    fun clearMentionSuggestions() {
        mentionSearchJob?.cancel()
        _mentionSuggestions.value = emptyList()
    }

    fun addComment(postId: String, content: String, parentId: String? = null, onSent: () -> Unit = {}) {
        if (content.isBlank() || _commentPosting.value || selectedPostId != postId) return
        val account = authService.getCurrentUserId()
        val request = ++commentPostRevision
        _commentPosting.value = true
        viewModelScope.launch {
            try {
            postRepository.addComment(postId, content, parentId)
                .onSuccess { newComment ->
                    if (selectedPostId != postId || account != authService.getCurrentUserId()) return@onSuccess
                    invalidateThreadSummary()
                    val current = _uiState.value
                    val currentPost = current.post
                    val nextComments = current.comments
                        .filterNot { it.id == newComment.id }
                        .plus(newComment)
                    _uiState.value = current.copy(
                        comments = nextComments,
                        post = currentPost?.copy(commentCount = currentPost.commentCount + if (current.comments.none { it.id == newComment.id }) 1 else 0),
                        isLoadingComments = false,
                        threadSummary = null,
                        threadSummaryError = null
                    )
                    onSent()
                    loadComments(postId)
                    refreshPostSnapshot(postId)
                }
                .onFailure {
                    if (selectedPostId != postId || account != authService.getCurrentUserId()) return@onFailure
                    android.widget.Toast.makeText(
                        context,
                        it.toUserFacingMessage("No se pudo enviar el comentario. Inténtalo de nuevo."),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (selectedPostId == postId && account == authService.getCurrentUserId())
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo enviar el comentario. Tu texto sigue aquí.")
            } finally {
                if (request == commentPostRevision) _commentPosting.value = false
            }
        }
    }

    fun addStickerComment(postId: String, stickerUrl: String, parentId: String? = null, onSent: () -> Unit = {}) {
        if (_commentPosting.value || selectedPostId != postId) return
        val account = authService.getCurrentUserId()
        val revision = ++commentPostRevision
        _commentPosting.value = true
        viewModelScope.launch {
            try {
            val result = postRepository.addStickerComment(postId, stickerUrl, parentId)
            currentCoroutineContext().ensureActive()
            result
                .onSuccess { newComment ->
                    if (selectedPostId != postId || account != authService.getCurrentUserId()) return@onSuccess
                    invalidateThreadSummary()
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        comments = current.comments.filterNot { it.id == newComment.id } + newComment,
                        post = current.post?.copy(commentCount = current.post.commentCount + if (current.comments.none { it.id == newComment.id }) 1 else 0),
                        isLoadingComments = false,
                        threadSummary = null,
                        threadSummaryError = null
                    )
                    onSent()
                    loadComments(postId)
                    refreshPostSnapshot(postId)
                }
                .onFailure {
                    if (selectedPostId != postId || account != authService.getCurrentUserId()) return@onFailure
                    android.widget.Toast.makeText(
                        context,
                        it.toUserFacingMessage("No se pudo enviar el sticker. Inténtalo de nuevo."),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (selectedPostId == postId && account == authService.getCurrentUserId())
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo enviar el sticker. Inténtalo de nuevo.")
            } finally {
                if (revision == commentPostRevision) _commentPosting.value = false
            }
        }
    }

    fun deleteComment(commentId: String) {
        val postId = _uiState.value.post?.id ?: return
        val account = authService.getCurrentUserId()
        viewModelScope.launch {
            postRepository.deleteComment(commentId)
                .onSuccess {
                    if (selectedPostId != postId || account != authService.getCurrentUserId()) return@onSuccess
                    invalidateThreadSummary()
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
                    if (selectedPostId != postId || account != authService.getCurrentUserId()) return@onFailure
                    android.util.Log.e("PostDetailViewModel", "Error deleting comment", it)
                    android.widget.Toast.makeText(context, it.toUserFacingMessage("No se pudo eliminar el comentario. Inténtalo de nuevo."), android.widget.Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun editComment(commentId: String, newContent: String, onSaved: () -> Unit = {}) {
        val postId = _uiState.value.post?.id ?: return
        val account = authService.getCurrentUserId()
        if (_commentEditing.value || newContent.isBlank()) return
        val revision = ++commentEditRevision
        _commentEditing.value = true
        viewModelScope.launch {
            try {
            postRepository.updateCommentContent(commentId, newContent)
                .onSuccess {
                    if (revision != commentEditRevision || selectedPostId != postId || account != authService.getCurrentUserId()) return@onSuccess
                    invalidateThreadSummary()
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
                    onSaved()
                }
                .onFailure {
                    android.util.Log.e("PostDetailViewModel", "Error editing comment", it)
                    if (selectedPostId == postId && account == authService.getCurrentUserId())
                        conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo guardar el comentario. Tu texto sigue aquí.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (selectedPostId == postId && account == authService.getCurrentUserId())
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo guardar el comentario. Tu texto sigue aquí.")
            } finally { if (revision == commentEditRevision) _commentEditing.value = false }
        }
    }

    fun toggleCommentReaction(comment: Comment, reactionType: String) {
        val postId = selectedPostId ?: return
        val account = authService.getCurrentUserId() ?: return
        val normalized = reactionType.uppercase().takeIf { it == "LIKE" || it == "DISLIKE" } ?: return
        if (!commentReactionsInFlight.add(comment.id)) return
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
            try {
            postRepository.setCommentReaction(comment.id, next).onFailure {
                if (selectedPostId != postId || account != authService.getCurrentUserId()) return@onFailure
                _uiState.value = _uiState.value.copy(
                    comments = _uiState.value.comments.map { current ->
                        if (current.id == comment.id) current.copy(myReaction = comment.myReaction, reactionCounts = comment.reactionCounts) else current
                    }
                )
                android.widget.Toast.makeText(context, "No se pudo guardar la reacción", android.widget.Toast.LENGTH_SHORT).show()
            }
            } finally {
                if (account == authService.getCurrentUserId()) commentReactionsInFlight.remove(comment.id)
            }
        }
    }

    private fun invalidateThreadSummary() {
        summaryJob?.cancel()
        summaryJob = null
        _uiState.update { it.copy(threadSummary = null, isSummarizingThread = false, threadSummaryError = null) }
    }

    fun requestThreadSummary(postId: String) {
        if (selectedPostId != postId || _uiState.value.isSummarizingThread) return
        val account = authService.getCurrentUserId()
        val epoch = postReadEpoch
        val textCommentCount = _uiState.value.comments.count { it.content.trim().length >= 3 }
        if (textCommentCount < 3) {
            _uiState.value = _uiState.value.copy(
                threadSummary = null,
                isSummarizingThread = false,
                threadSummaryError = "Se necesitan al menos tres comentarios con texto para preparar un resumen."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
                isSummarizingThread = true,
                threadSummaryError = null
            )
        summaryJob = viewModelScope.launch {
            val result = conectate.com.core.network.runReadWithDeadline(30_000) { postRepository.summarizePostThread(postId).getOrThrow() }
            currentCoroutineContext().ensureActive()
            if (!isCurrentPost(postId, account, epoch)) return@launch
            result
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

    suspend fun reportPost(post: Post, reason: String, details: String?, evidenceUri: android.net.Uri?): Result<Unit> =
            postRepository.submitContentReport(
                contentType = "post",
                contentId = post.id,
                reportedUserId = post.userId,
                reportedUserName = post.userName,
                reason = reason,
                details = details,
                evidenceUri = evidenceUri
            )

    suspend fun reportComment(comment: Comment, reason: String, details: String?, evidenceUri: android.net.Uri?): Result<Unit> =
            postRepository.submitContentReport(
                contentType = "comment",
                contentId = comment.id,
                reportedUserId = comment.userId,
                reportedUserName = comment.userName ?: "Usuario",
                reason = reason,
                details = details,
                evidenceUri = evidenceUri
            )

    private suspend fun refreshPostSnapshot(postId: String) {
        val account = authService.getCurrentUserId()
        val epoch = postReadEpoch
        val reactionReadVersion = reactionWriter.readVersion()
        postRepository.getPostById(postId).onSuccess { freshPost ->
            if (isCurrentPost(postId, account, epoch)) _uiState.value = _uiState.value.copy(
                post = reactionWriter.reconcile(freshPost, reactionReadVersion))
        }
    }

    private fun isCurrentPost(postId: String, account: String?, epoch: Long): Boolean =
        epoch == postReadEpoch && selectedPostId == postId && account == authService.getCurrentUserId()

    
    private fun extractActiveMentionQuery(text: String): String? {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0 || atIndex == text.lastIndex) return null

        val mentionCandidate = text.substring(atIndex + 1)
        if (mentionCandidate.any { it.isWhitespace() }) return null
        if (mentionCandidate.length > 30) return null

        return mentionCandidate.trim()
    }
}
