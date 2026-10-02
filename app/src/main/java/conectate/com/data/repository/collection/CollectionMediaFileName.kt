package conectate.com.data.repository.collection

import java.util.UUID

/** Parallel group/community uploads must never share an object key. */
internal fun collectionMediaFileName(
    prefix: String,
    ownerId: String,
    extension: String,
    timestampEpochMs: Long = System.currentTimeMillis(),
): String = "${prefix}_${ownerId}_${timestampEpochMs}_${UUID.randomUUID()}.${extension.removePrefix(".")}"
