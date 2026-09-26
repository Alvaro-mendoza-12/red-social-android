package conectate.com.core.media

import java.util.Locale

/** Capture before fetching a profile, not after its remote response arrives. */
internal data class ProfileIdentityRequest(val accountId: String, val generation: Long) {
    fun belongsTo(currentAccountId: String?): Boolean {
        val captured = accountId.trim().lowercase(Locale.ROOT)
        return captured.isNotEmpty() && captured == currentAccountId?.trim()?.lowercase(Locale.ROOT)
    }
}
