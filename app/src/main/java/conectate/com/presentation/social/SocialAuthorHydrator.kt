package conectate.com.presentation.social

import conectate.com.data.model.Post
import conectate.com.data.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Optional identity enrichment runs after the posts are visible. */
internal class SocialAuthorHydrator(
    private val scope: CoroutineScope,
    private val loadProfiles: suspend (List<String>) -> Result<List<User>>,
) {
    private var revision = 0L
    private var job: Job? = null

    fun cancel() {
        revision++
        job?.cancel()
        job = null
    }

    fun refresh(posts: List<Post>, apply: (List<User>) -> Unit) {
        cancel()
        val authorIds = posts.map { it.userId.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        if (authorIds.isEmpty()) return
        val requestRevision = revision
        job = scope.launch {
            try {
                val profiles = withTimeoutOrNull(5_000L) { loadProfiles(authorIds).getOrNull() }
                    ?: return@launch
                currentCoroutineContext().ensureActive()
                if (requestRevision == revision && profiles.isNotEmpty()) apply(profiles)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The delivered feed already contains usable author snapshots.
            }
        }
    }
}

/** Merge onto the latest list so a late profile reply cannot undo a local edit. */
internal fun applyCanonicalPostAuthors(posts: List<Post>, users: List<User>, confirmedUpdate: Boolean = false): List<Post> {
    val profiles = users.associateBy { it.id.trim().lowercase() }
    return posts.map { post ->
        val profile = profiles[post.userId.trim().lowercase()] ?: return@map post
        post.copy(
            // The feed snapshot already contains an author identity. A partial
            // profile response can omit the avatar/name, so never replace a
            // useful visible value with a transient blank while hydrating.
            userName = profile.displayName.takeUnless { it.isBlank() || it.equals("Usuario", ignoreCase = true) }
                ?: post.userName,
            userProfileImage = if (confirmedUpdate) profile.profileImage.ifBlank { null }
                else profile.profileImage.ifBlank { post.userProfileImage },
            isVerified = profile.is_verified,
        ).also { it.isSensitive = post.isSensitive }
    }
}

/** A partial feed row must reuse an already-known photo before its first visible frame. */
internal fun fillMissingPostAuthorIdentities(posts: List<Post>, users: List<User>): List<Post> {
    val profiles = users.associateBy { it.id.trim().lowercase() }
    return posts.map { post ->
        val profile = profiles[post.userId.trim().lowercase()] ?: return@map post
        val image = post.userProfileImage.orEmpty().ifBlank { profile.profileImage }
        val name = post.userName.takeUnless { it.isBlank() || it.equals("Usuario", true) }
            ?: profile.displayName
        if (image == post.userProfileImage && name == post.userName) post else post.copy(
            userProfileImage = image,
            userName = name,
        ).also { it.isSensitive = post.isSensitive }
    }
}
