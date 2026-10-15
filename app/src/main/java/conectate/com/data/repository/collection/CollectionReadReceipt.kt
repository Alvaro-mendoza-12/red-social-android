package conectate.com.data.repository.collection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A notification must remain available until its receipt is confirmed. */
internal suspend fun applyCollectionReadReceipt(
    requireRemoteConfirmation: Boolean,
    isSessionCurrent: () -> Boolean,
    synchronize: suspend () -> Boolean,
    applyLocal: suspend () -> Unit
) {
    currentCoroutineContext().ensureActive()
    if (!isSessionCurrent()) throw CancellationException("Read receipt session changed")
    val confirmed = synchronize()
    currentCoroutineContext().ensureActive()
    if (!isSessionCurrent()) throw CancellationException("Read receipt session changed")
    check(!requireRemoteConfirmation || confirmed) { "Read receipt was not confirmed" }
    applyLocal()
}
