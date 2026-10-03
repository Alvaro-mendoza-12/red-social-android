package conectate.com.presentation.profile

import conectate.com.data.localfallback.SystemAnnouncement
import kotlinx.coroutines.CancellationException

internal data class AnnouncementAdminMutation(val notices: List<SystemAnnouncement>?)

/** A failed list refresh must not turn an already committed publication into a failed send. */
internal suspend fun commitAnnouncementAndRefresh(
    commit: suspend () -> Unit,
    refresh: suspend () -> List<SystemAnnouncement>,
): AnnouncementAdminMutation {
    commit()
    val notices = try { refresh() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    return AnnouncementAdminMutation(notices)
}
