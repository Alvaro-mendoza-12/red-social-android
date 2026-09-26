package conectate.com.data.repository

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Persists the operation ID before sending. A lost response must not become a second purchase. */
internal class GiftSendCoordinator(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
) {
    private val sending = Mutex()

    fun hasPending(operation: String): Boolean = !read(operation).isNullOrBlank()

    suspend fun <T> execute(operation: String, proposedKey: String, send: suspend (String) -> T): Result<T> {
        if (!sending.tryLock()) return Result.failure(IllegalStateException("Ya se está confirmando un regalo."))
        return try {
            val key = read(operation)?.takeIf { it.isNotBlank() } ?: proposedKey.also {
                require(it.isNotBlank())
                write(operation, it)
            }
            val receipt = send(key)
            write(operation, null)
            Result.success(receipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep the same ID even after an HTTP error: an earlier attempt may have committed.
            Result.failure(error)
        } finally {
            sending.unlock()
        }
    }
}

internal fun giftOperationKey(accountId: String, receiverId: String, liveId: String?, giftId: String, isPublic: Boolean): String {
    val canonical = listOf(accountId.trim(), receiverId.trim(), liveId?.trim().orEmpty(), giftId.trim(), isPublic.toString())
        .joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
