package conectate.com.core.messaging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Ephemeral transport progress; never shared between account identities. */
object MessageUploadProgress {
    data class Key(val accountId: String, val messageId: String)
    private val mutable = MutableStateFlow<Map<Key, Float>>(emptyMap())
    val transfers = mutable.asStateFlow()

    fun key(accountId: String, messageId: String) = Key(accountId.trim().lowercase(), messageId.trim().lowercase())
    fun update(accountId: String, messageId: String, fraction: Float) {
        if (accountId.isBlank() || messageId.isBlank() || !fraction.isFinite()) return
        mutable.update { it + (key(accountId, messageId) to fraction.coerceIn(0f, 1f)) }
    }
    fun finish(accountId: String, messageId: String) {
        mutable.update { it - key(accountId, messageId) }
    }
}
