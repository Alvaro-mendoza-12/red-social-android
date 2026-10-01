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
