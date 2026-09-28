package conectate.com.presentation.home

import conectate.com.data.model.PlayMatchConnection
import conectate.com.data.model.PlayMatchMatch
import java.time.Instant

internal object HomeNoveltyPolicy {
    fun instant(value: String?): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    fun newest(values: Iterable<String?>): Instant? = values.mapNotNull(::instant).maxOrNull()

    fun hasNewer(latest: Instant?, seen: Instant): Boolean = latest?.isAfter(seen) == true

    fun gameEvents(
        connections: List<PlayMatchConnection>,
        matches: List<PlayMatchMatch>,
        now: Instant = Instant.now(),
    ): Set<String> = buildSet {
        connections.filter {
            (it.direction == "INCOMING" && it.status == "PENDING") || it.status == "ACCEPTED"
        }.forEach { add("connection:${it.id}:${it.status}:${it.updatedAt.orEmpty()}") }
        matches.filter {
            it.status == "ACCEPTED" ||
                (it.status == "PENDING" && instant(it.expiresAt)?.isAfter(now) == true)
        }.forEach { add("match:${it.id}:${it.status}") }
    }
}
