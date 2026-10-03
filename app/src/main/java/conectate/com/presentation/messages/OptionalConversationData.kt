package conectate.com.presentation.messages

import conectate.com.core.network.runReadWithDeadline

/** Optional profile/badge failures must not terminate an already opened conversation. */
internal suspend fun <T> optionalConversationData(block: suspend () -> T): T? =
    runReadWithDeadline(3_000, block).getOrNull()
