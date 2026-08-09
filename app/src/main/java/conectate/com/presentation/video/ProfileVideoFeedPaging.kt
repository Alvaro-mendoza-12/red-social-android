package conectate.com.presentation.video

/**
 * Pure paging rules for a profile-only video feed. Keeping these rules outside
 * Compose makes the selected profile video deterministic and unit-testable.
 */
internal object ProfileVideoFeedPaging {
    const val DefaultPageSize = 24
    const val MaxResidentPages = 3

    fun initialPage(initialIndex: Int, pageSize: Int = DefaultPageSize): PageRequest {
        val safePageSize = pageSize.coerceAtLeast(1)
        val safeIndex = initialIndex.coerceAtLeast(0)
        return PageRequest(
            offset = (safeIndex - safePageSize / 2).coerceAtLeast(0),
            limit = safePageSize
        )
    }

    fun previousPage(firstOffset: Int, pageSize: Int = DefaultPageSize): PageRequest? {
        val safeFirstOffset = firstOffset.coerceAtLeast(0)
        if (safeFirstOffset == 0) return null

        val safePageSize = pageSize.coerceAtLeast(1)
        val offset = (safeFirstOffset - safePageSize).coerceAtLeast(0)
        return PageRequest(offset = offset, limit = safeFirstOffset - offset)
    }

    fun <T> boundedWindow(
        items: List<T>,
        pageSize: Int = DefaultPageSize,
        dropFromStart: Boolean
    ): WindowResult<T> {
        val maximum = pageSize.coerceAtLeast(1) * MaxResidentPages
        if (items.size <= maximum) return WindowResult(items, 0, 0)
        val overflow = items.size - maximum
        return if (dropFromStart) {
            WindowResult(items.drop(overflow), removedFromStart = overflow, removedFromEnd = 0)
        } else {
            WindowResult(items.dropLast(overflow), removedFromStart = 0, removedFromEnd = overflow)
        }
    }
}

internal data class PageRequest(
    val offset: Int,
    val limit: Int
)

internal data class WindowResult<T>(
    val items: List<T>,
    val removedFromStart: Int,
    val removedFromEnd: Int
)
