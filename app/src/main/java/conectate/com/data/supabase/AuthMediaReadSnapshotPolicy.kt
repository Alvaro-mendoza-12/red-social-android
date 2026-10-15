package conectate.com.data.supabase

import conectate.com.core.media.StorageReadCredential
import conectate.com.core.media.StorageReadIdentity

/** Pure coherent snapshot of the published in-memory session; never performs IO. */
internal object AuthMediaReadSnapshotPolicy {
    fun snapshot(
        accountId: String?, accessToken: String?, generationBefore: Long,
        generationAfter: Long, registrationInProgress: Boolean,
    ): StorageReadCredential? {
        if (registrationInProgress || generationBefore != generationAfter) return null
        val account = accountId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val token = accessToken?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return StorageReadCredential(StorageReadIdentity(account, generationAfter), token)
    }
}
