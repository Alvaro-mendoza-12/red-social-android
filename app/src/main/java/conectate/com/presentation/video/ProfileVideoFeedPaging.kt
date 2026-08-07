package conectate.com.presentation.video

/**
 * Pure paging rules for a profile-only video feed. Keeping these rules outside
 * Compose makes the selected profile video deterministic and unit-testable.
 */
internal object ProfileVideoFeedPaging {
    const val DefaultPageSize = 24

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
}

internal data class PageRequest(
    val offset: Int,
    val limit: Int
)
