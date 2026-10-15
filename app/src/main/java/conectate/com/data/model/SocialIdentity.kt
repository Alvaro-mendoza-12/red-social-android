package conectate.com.data.model

import java.time.Instant

/** Friends in the feed means reciprocal following, independently of contact requests. */
enum class AuthorFollowState(val label: String) {
    NOT_FOLLOWING("Seguir"), FOLLOWING("Seguido"), MUTUAL("Amigos");
    companion object {
        fun from(following: Boolean, followedBy: Boolean): AuthorFollowState =
            if (!following) NOT_FOLLOWING else if (followedBy) MUTUAL else FOLLOWING
    }
}

/** Input must come from the account-scoped, server-authorized moments feed. */
fun activeAvatarMoments(moments: List<Moment>, now: Instant): Map<String, Moment> =
    moments.filter { moment ->
        val expiry = runCatching {
            moment.expiresAt?.takeIf(String::isNotBlank)?.let(Instant::parse)
                ?: Instant.parse(moment.createdAt).plusSeconds(moment.durationHours.coerceIn(1, 168) * 3600L)
        }.getOrNull()
        moment.id.isNotBlank() && expiry != null && expiry.isAfter(now)
    }.groupBy { it.userId.trim().lowercase() }.mapValues { (_, items) ->
        items.sortedWith(compareBy<Moment> { it.isViewed }.thenBy { it.createdAt.orEmpty() }).first()
    }

/** The viewport supplies the most recent authors first; arbitrary query text is never forwarded. */
fun momentAvatarOwnerIds(values: Iterable<String>): List<String> = values.asSequence()
    .map { it.trim().lowercase() }
    .filter { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }
    .distinct().take(100).toList()
