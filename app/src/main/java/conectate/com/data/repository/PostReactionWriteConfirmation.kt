package conectate.com.data.repository

import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.model.Post
import java.io.IOException
import kotlinx.coroutines.CancellationException

private const val REACTION_CONFIRMATION_TIMEOUT_MS = 2_500L

/** A lost response is ambiguous because the server may already have committed the write. */
internal suspend fun confirmPostReactionWrite(
    response: Result<*>,
    postId: String,
    expectedReaction: String?,
    readPost: suspend () -> Result<Post>,
): Result<Unit> {
    val error = response.exceptionOrNull() ?: return Result.success(Unit)
    if (error is CancellationException) throw error
    if (error !is IOException) return Result.failure(error)

    val post = runReadWithDeadline(REACTION_CONFIRMATION_TIMEOUT_MS) {
        readPost().getOrThrow()
    }.getOrNull()?.takeIf { it.id == postId } ?: return Result.failure(error)

    val persisted = post.myReaction ?: "LIKE".takeIf { post.likedByMe }
    return if (persisted == expectedReaction) Result.success(Unit) else Result.failure(error)
}
