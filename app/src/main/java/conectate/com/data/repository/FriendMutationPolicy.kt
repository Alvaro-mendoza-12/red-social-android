package conectate.com.data.repository

/** Both directional deletes must succeed, including a successful empty delete. */
internal fun friendshipDeleteCompleted(outgoingStatus: Int, incomingStatus: Int): Boolean =
    outgoingStatus in 200..299 && incomingStatus in 200..299
