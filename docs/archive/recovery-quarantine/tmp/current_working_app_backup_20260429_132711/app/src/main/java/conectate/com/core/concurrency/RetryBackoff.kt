package conectate.com.core.concurrency

fun nextRetryDelayMs(currentDelayMs: Long, maxDelayMs: Long): Long {
    val safeMaxDelayMs = maxDelayMs.coerceAtLeast(1L)
    val safeCurrentDelayMs = currentDelayMs.coerceAtLeast(1L)
    return (safeCurrentDelayMs * 2).coerceAtMost(safeMaxDelayMs)
}


