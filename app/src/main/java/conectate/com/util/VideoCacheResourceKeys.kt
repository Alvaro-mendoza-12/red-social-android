package conectate.com.util

import conectate.com.core.media.StorageReadSession
import conectate.com.data.localfallback.UbuntuStorageUrl

/** A recovery evicts this object's revisions without touching another account or session. */
internal fun videoCacheKeysToRemove(url: String, keys: Set<String>): Set<String> {
    val stableKey = UbuntuStorageUrl.stableCacheKey(url) ?: return emptySet()
    val revisionPrefix = StorageReadSession.scopedCacheKey(url, "$stableKey:revision:")
    return keys.filterTo(linkedSetOf()) { key ->
        key == stableKey || key.startsWith(revisionPrefix)
    }
}
