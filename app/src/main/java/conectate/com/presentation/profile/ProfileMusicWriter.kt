package conectate.com.presentation.profile

import conectate.com.data.model.ProfileMusicSummary
import conectate.com.data.model.withReaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Serialize accepted writes; fast second taps change intent without racing the first HTTP request. */
internal class ProfileMusicWriter(
    private val scope: CoroutineScope,
    private val current: () -> ProfileMusicSummary?,
    private val write: suspend (ProfileMusicSummary, String?) -> Result<ProfileMusicSummary>,
    private val publish: (ProfileMusicSummary) -> Unit,
    private val failed: () -> Unit,
) {
    private var job: Job? = null
    private var generation = 0L
    private var desired: String? = null
    val pending: Boolean get() = job != null

    fun clear() { generation++; job?.cancel(); job = null }

    fun select(value: String) {
        if (value !in conectate.com.data.model.profileMusicReactions) return
        val before = current() ?: return
        if (!pending) desired = before.reaction
        desired = value.takeUnless { it == desired }
        publish(before.withReaction(desired))
        if (pending) return
        val epoch = generation
        job = scope.launch(start = CoroutineStart.LAZY) {
            var confirmed = before
            try {
                while (epoch == generation && confirmed.reaction != desired) {
                    val target = desired
                    val response = write(confirmed, target).getOrThrow()
                    if (epoch != generation) return@launch
                    check(response.musicKey == before.musicKey) { "La música ha cambiado" }
                    confirmed = response
                    publish(confirmed.withReaction(desired))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == generation) { publish(confirmed); failed() }
            } finally { if (epoch == generation) job = null }
        }
        job?.start()
    }
}
