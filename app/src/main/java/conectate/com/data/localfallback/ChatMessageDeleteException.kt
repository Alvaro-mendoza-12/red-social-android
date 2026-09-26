package conectate.com.data.localfallback

/** Typed only for this endpoint: a missing message is different from a missing route/account. */
class ChatMessageDeleteException(val statusCode: Int, val errorCode: String) :
    Exception(errorCode.ifBlank { "Message deletion failed ($statusCode)" })
