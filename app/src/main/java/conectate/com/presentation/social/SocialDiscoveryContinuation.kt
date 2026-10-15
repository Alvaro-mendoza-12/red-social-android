package conectate.com.presentation.social

/** Exhausting the personalized shortlist does not exhaust the public Discover feed. */
internal data class SocialDiscoveryContinuation(val personalized: Boolean, val publicOffset: Int?)

internal fun socialDiscoveryContinuation(hasMore: Boolean, hasUnconsumedCursor: Boolean): SocialDiscoveryContinuation =
    if (hasMore && hasUnconsumedCursor) SocialDiscoveryContinuation(true, null)
    else SocialDiscoveryContinuation(false, 0)
