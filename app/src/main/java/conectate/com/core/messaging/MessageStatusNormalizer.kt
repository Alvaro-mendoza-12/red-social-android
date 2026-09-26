package conectate.com.core.messaging

import conectate.com.util.Constants

object MessageStatusNormalizer {

    fun normalize(status: String?): String {
        var normalized = status?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            Constants.MESSAGE_STATUS_READ -> Constants.MESSAGE_STATUS_READ
            Constants.MESSAGE_STATUS_DELIVERED, "received" -> Constants.MESSAGE_STATUS_DELIVERED
            "sending" -> "sending"
            "failed" -> "failed"
            "pending_delete" -> "pending_delete"
            Constants.MESSAGE_STATUS_SENT, "" -> Constants.MESSAGE_STATUS_SENT
            else -> normalized
        }
    }

    fun canonical(
        status: String?,
        deliveredAt: String?,
        readAt: String?
    ): String {
        var normalized = normalize(status)
        return when {
            !readAt.isNullOrBlank() || normalized == Constants.MESSAGE_STATUS_READ -> Constants.MESSAGE_STATUS_READ
            !deliveredAt.isNullOrBlank() || normalized == Constants.MESSAGE_STATUS_DELIVERED -> Constants.MESSAGE_STATUS_DELIVERED
            normalized.isNotBlank() -> normalized
            else -> Constants.MESSAGE_STATUS_SENT
        }
    }

    /** Status for a successful send, preserving a stronger remote receipt. */
    fun acknowledgedAfterSend(
        status: String?,
        deliveredAt: String?,
        readAt: String?
    ): String {
        return when {
            !readAt.isNullOrBlank() || normalize(status) == Constants.MESSAGE_STATUS_READ ->
                Constants.MESSAGE_STATUS_READ
            !deliveredAt.isNullOrBlank() || normalize(status) == Constants.MESSAGE_STATUS_DELIVERED ->
                Constants.MESSAGE_STATUS_DELIVERED
            else -> Constants.MESSAGE_STATUS_SENT
        }
    }

    fun isUnread(status: String?, readAt: String?): Boolean {
        if (!readAt.isNullOrBlank()) return false
        return when (normalize(status)) {
            Constants.MESSAGE_STATUS_SENT,
            Constants.MESSAGE_STATUS_DELIVERED -> true
            else -> false
        }
    }

    fun isEligibleForReadTransition(status: String?): Boolean {
        return when (normalize(status)) {
            Constants.MESSAGE_STATUS_SENT,
            Constants.MESSAGE_STATUS_DELIVERED -> true
            else -> false
        }
    }
}



