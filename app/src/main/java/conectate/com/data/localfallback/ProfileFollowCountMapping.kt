package conectate.com.data.localfallback

import conectate.com.data.model.User

/** GET profile envelopes compute these from relations; denormalized profile columns can be stale. */
internal fun User.withComputedFollowCounts(stats: LocalFallbackProfileStats): User = copy(
    followers_count = stats.followers.coerceAtLeast(0),
    following_count = stats.following.coerceAtLeast(0),
)

/** A profile PATCH returns stats:{} and must not erase counters loaded by a profile GET. */
internal fun User.retainingFollowCountsFrom(previous: User?): User =
    if (previous?.id?.equals(id, ignoreCase = true) == true) copy(
        followers_count = previous.followers_count ?: followers_count,
        following_count = previous.following_count ?: following_count,
    ) else this
