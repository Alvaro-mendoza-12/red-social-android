package conectate.com.data.livekit

/** Keep the host publisher connected while media controls are temporarily off. */
internal fun shouldFollowLiveHostControls(role: String): Boolean = role == "host"
