package conectate.com.presentation.messages

internal enum class MessageDeliveryState { COMPLETE, WAITING, UPLOADING, FAILED }

/** A queued send is retried by the durable outbox; only a failed send needs a retry button. */
internal fun messageDeliveryState(status: String, uploadProgress: Float?): MessageDeliveryState =
    when (status.trim().lowercase()) {
        "failed" -> MessageDeliveryState.FAILED
        "pending", "sending" -> if (uploadProgress?.isFinite() == true) {
            MessageDeliveryState.UPLOADING
        } else {
            MessageDeliveryState.WAITING
        }
        else -> MessageDeliveryState.COMPLETE
    }
