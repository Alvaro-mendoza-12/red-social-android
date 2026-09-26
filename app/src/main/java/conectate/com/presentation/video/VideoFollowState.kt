package conectate.com.presentation.video

/** Local follow mutations take precedence over reads started before them. */
internal data class VideoFollowState(
    val followed: Set<String> = emptySet(),
    val pending: Set<String> = emptySet(),
    val revision: Long = 0,
) {
    fun begin(userId: String): VideoFollowState {
        val id = userId.trim().lowercase()
        if (id.isBlank() || id in followed || id in pending) return this
        return copy(followed = followed + id, pending = pending + id, revision = revision + 1)
    }

    fun complete(userId: String, success: Boolean): VideoFollowState {
        val id = userId.trim().lowercase()
        if (id !in pending) return this
        return copy(
            followed = if (success) followed + id else followed - id,
            pending = pending - id,
            revision = revision + 1,
        )
    }

    fun reconcile(candidateIds: Collection<String>, remoteFollowedIds: Set<String>, requestRevision: Long): VideoFollowState {
        if (requestRevision != revision) return this
        val candidates = candidateIds.mapTo(mutableSetOf()) { it.trim().lowercase() }
        val remote = remoteFollowedIds.mapTo(mutableSetOf()) { it.trim().lowercase() }.intersect(candidates)
        return copy(followed = (followed - candidates) + remote + pending)
    }
}
