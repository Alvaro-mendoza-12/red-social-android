package conectate.com.data.repository

import conectate.com.data.model.StoredAccountSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serialize credential edits and publish only snapshots confirmed by secure storage. */
internal class StoredAccountsStore(
    initial: List<StoredAccountSession>,
    private val persist: (List<StoredAccountSession>) -> Unit,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()

    fun update(transform: (List<StoredAccountSession>) -> List<StoredAccountSession>): List<StoredAccountSession> =
        synchronized(lock) {
            val next = transform(mutable.value)
            persist(next)
            mutable.value = next
            next
        }
}
