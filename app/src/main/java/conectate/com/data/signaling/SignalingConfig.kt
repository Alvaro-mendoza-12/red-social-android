package conectate.com.data.signaling

import conectate.com.data.localfallback.RuntimeBackendEndpoint

object SignalingConfig {
    val BASE_URL: String
        get() = RuntimeBackendEndpoint.baseUrl.trimEnd('/')
    val TURN_CREDENTIALS_URL: String get() = "$BASE_URL/turn-credentials"
    val CINEMA_RESOLVE_URL: String get() = "$BASE_URL/resolve-cinema"

    // Default namespace (Voice Rooms)
    val VOICE_URL: String get() = BASE_URL

    // Independent namespaces
    val CALL_URL: String get() = "$BASE_URL/call"
    val LIVE_URL: String get() = "$BASE_URL/live"
}

