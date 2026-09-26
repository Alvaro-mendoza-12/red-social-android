package conectate.com.presentation.common

/** Bounded automatic recovery for the first page of Social and Videos. */
object FeedRecoveryPolicy {
    const val MaxAutomaticAttempts = 2

    fun shouldRetry(
        isConnected: Boolean,
        hasContent: Boolean,
        isLoading: Boolean,
        hasError: Boolean,
        attempts: Int,
    ): Boolean = isConnected &&
        !hasContent &&
        !isLoading &&
        hasError &&
        attempts in 0 until MaxAutomaticAttempts

    fun delayMillis(attemptsAlreadyMade: Int): Long =
        1_500L * (attemptsAlreadyMade.coerceAtLeast(0) + 1L)
}
