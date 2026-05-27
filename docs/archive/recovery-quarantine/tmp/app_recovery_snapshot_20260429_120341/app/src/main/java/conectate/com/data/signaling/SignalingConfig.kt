package conectate.com.data.signaling

object SignalingConfig {
    const val BASE_URL = "https://voice.srv1495648.hstgr.cloud"
    const val TURN_CREDENTIALS_URL = "$BASE_URL/turn-credentials"
    const val CINEMA_RESOLVE_URL = "$BASE_URL/resolve-cinema"

    // Default namespace (Voice Rooms)
    const val VOICE_URL = BASE_URL

    // Independent namespaces
    const val CALL_URL = "$BASE_URL/call"
    const val LIVE_URL = "$BASE_URL/live"
}


