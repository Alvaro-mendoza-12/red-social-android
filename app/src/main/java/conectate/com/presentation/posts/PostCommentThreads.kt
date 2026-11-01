package conectate.com.presentation.posts

import conectate.com.data.model.Comment

/** Every available comment remains visible, even if its parent is outside the page. */
fun organizeThreaded(comments: List<Comment>): List<Pair<Comment, Int>> {
    val unique = comments.distinctBy { it.id }
    val ids = unique.mapTo(hashSetOf()) { it.id }
    val children = unique.groupBy { it.parentId }
    val visited = hashSetOf<String>()
    val result = ArrayList<Pair<Comment, Int>>(unique.size)
    fun appendThread(root: Comment) {
        val pending = java.util.ArrayDeque<Pair<Comment, Int>>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            val (comment, level) = pending.removeLast()
            if (!visited.add(comment.id)) continue
            result.add(comment to level.coerceAtMost(3))
            children[comment.id].orEmpty().asReversed().forEach { pending.addLast(it to (level + 1)) }
        }
    }
    unique.filter { it.parentId == null || it.parentId !in ids || it.parentId == it.id }.forEach(::appendThread)
    // Malformed historical cycles are displayed once rather than hidden or recursed forever.
    unique.filter { it.id !in visited }.forEach(::appendThread)
    return result
}
