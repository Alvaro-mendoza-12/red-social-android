package conectate.com.presentation.messages

/** A task reopened from Recents is not a new external Sharesheet delivery. */
internal fun shouldAcceptRestoredShare(fromHistory: Boolean, alreadyConsumed: Boolean): Boolean =
    !fromHistory && !alreadyConsumed
