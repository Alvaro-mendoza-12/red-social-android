package conectate.com.data.signaling

import conectate.com.BuildConfig

object SignalingConfig {
    val BASE_URL: String = BuildConfig.FALLBACK_API_URL.trim().ifBlank {
        "https://voice.srv1495648.hstgr.cloud"
    }.trimEnd('/')
    val TURN_CREDENTIALS_URL = "$BASE_URL/turn-credentials"
    val CINEMA_RESOLVE_URL = "$BASE_URL/resolve-cinema"

    // Default namespace (Voice Rooms)
    val VOICE_URL = BASE_URL

    // Independent namespaces
    val CALL_URL = "$BASE_URL/call"
    val LIVE_URL = "$BASE_URL/live"
}

