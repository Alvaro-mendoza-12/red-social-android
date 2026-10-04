package conectate.com.data.localfallback

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class SystemAnnouncement(
    val id: String, val kind: String, val title: String, val body: String,
    val expiresAt: String,
    val startsAt: String? = null, val endsAt: String? = null,
    val versionCode: Int? = null, val updateUrl: String? = null,
    val createdAt: String? = null, val revokedAt: String? = null,
) {
    fun isVisible(installedVersion: Int, now: Instant = Instant.now()): Boolean =
        revokedAt == null && runCatching { Instant.parse(expiresAt).isAfter(now) }.getOrDefault(false) &&
            (kind != "update" || (versionCode ?: 0) > installedVersion)
}
@Serializable data class SystemAnnouncementEnvelope(val items: List<SystemAnnouncement> = emptyList())
@Serializable data class PublishSystemAnnouncement(
    val id: String, val kind: String, val title: String, val body: String, val expiresAt: String,
    val startsAt: String? = null, val endsAt: String? = null, val versionCode: Int? = null,
)

/** Active maintenance first, then upcoming maintenance, updates and news. */
internal fun orderedSystemAnnouncements(
    items: List<SystemAnnouncement>,
    installedVersion: Int,
    now: Instant = Instant.now(),
): List<SystemAnnouncement> = items.filter { it.isVisible(installedVersion, now) }
    .sortedWith(compareBy<SystemAnnouncement> { notice ->
        when (notice.kind) {
            "maintenance" -> {
                val start = runCatching { Instant.parse(notice.startsAt) }.getOrNull()
                val end = runCatching { Instant.parse(notice.endsAt) }.getOrNull()
                when {
                    start != null && end != null && !start.isAfter(now) && end.isAfter(now) -> 0
                    start != null && start.isAfter(now) -> 1
                    else -> 3
                }
            }
            "update" -> 2
            else -> 3
        }
    }.thenByDescending { runCatching { Instant.parse(it.createdAt) }.getOrDefault(Instant.EPOCH) }
        .thenBy { it.id }).distinctBy { it.id }
