package conectate.com.presentation.social

/** One visit to a page. Revisiting creates a fresh session; duplicate/stale callbacks do nothing. */
internal class HighlightPlaybackSession(val itemId: String, val page: Int, val count: Int) {
    var completed: Boolean = false
        private set

    fun finish(
        callbackItemId: String, visiblePage: Int, scrolling: Boolean, resumed: Boolean,
        currentSession: HighlightPlaybackSession = this,
    ): Int? {
        if (currentSession !== this || completed || callbackItemId != itemId ||
            visiblePage != page || scrolling || !resumed) return null
        completed = true
        // count is the close sentinel, otherwise this is the next page.
        return (page + 1).coerceAtMost(count)
    }
}
