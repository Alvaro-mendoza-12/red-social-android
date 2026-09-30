package conectate.com.data.webrtc

/** Native WebRTC accessors may wrap the same sender in distinct Java objects. */
internal fun isSameMediaSender(senderId: String?, primarySenderId: String?): Boolean =
    !senderId.isNullOrBlank() && senderId == primarySenderId
