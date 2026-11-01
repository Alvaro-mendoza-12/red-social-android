package conectate.com.presentation.posts

/** Acknowledging an older send must not erase a draft edited in the meantime. */
internal class PostCommentDraftRevision {
    private var revision = 0L
    fun changed() { revision++ }
    fun snapshot(): Long = revision
    fun acknowledge(submittedRevision: Long): Boolean {
        if (submittedRevision != revision) return false
        revision++
        return true
    }
}
