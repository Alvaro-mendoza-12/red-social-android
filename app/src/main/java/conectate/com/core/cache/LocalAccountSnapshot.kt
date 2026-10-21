package conectate.com.core.cache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Disk reads and JSON decoding must not block first paint or cross accounts. */
internal suspend fun <T> readLocalAccountSnapshot(
    accountId: () -> String?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    read: (String?) -> T,
): T? {
    val owner = accountId()
    val snapshot = withContext(dispatcher) { read(owner) }
    return snapshot.takeIf { accountId() == owner }
}
