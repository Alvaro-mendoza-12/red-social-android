package conectate.com.data.localfallback

internal fun publicFeedNextOffset(offset: Int, limit: Int, itemCount: Int,
    hasMore: Boolean?, serverNextOffset: Int?): Int? {
    if (!(hasMore ?: (itemCount >= limit))) return null
    return (serverNextOffset ?: (offset + itemCount)).takeIf { it > offset && it <= 10_000 }
}
