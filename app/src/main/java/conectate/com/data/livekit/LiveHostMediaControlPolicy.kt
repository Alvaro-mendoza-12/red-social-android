package conectate.com.data.livekit

/** A receive-only preparation must not start publishing because a state changes. */
internal fun shouldFollowLiveHostControls(
    role: String,
    requestedAudio: Boolean,
    requestedVideo: Boolean,
    activeCrossLiveBattle: Boolean
): Boolean = role == "host" && (requestedAudio || requestedVideo || activeCrossLiveBattle)
