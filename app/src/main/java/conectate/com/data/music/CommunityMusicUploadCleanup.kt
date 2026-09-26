package conectate.com.data.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Best effort rollback remains bounded even when the publishing job is cancelled. */
internal suspend fun cleanupUncommittedCommunityMusicUploads(
    uploadedObjects: List<Pair<String, String>>,
    delete: suspend (bucket: String, key: String) -> Unit,
) {
    if (uploadedObjects.isEmpty()) return
    withContext(NonCancellable) {
        withTimeoutOrNull(5_000L) {
            uploadedObjects.asReversed().forEach { (bucket, key) ->
                try {
                    delete(bucket, key)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A cleanup failure must not hide the original upload error.
                }
            }
        }
    }
}
