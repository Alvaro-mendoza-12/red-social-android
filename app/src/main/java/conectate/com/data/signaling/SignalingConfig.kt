package conectate.com.data.signaling

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint

object SignalingConfig {
    val BASE_URL: String
        get() = RuntimeBackendEndpoint.baseUrl
            .trim()
            .ifBlank { BuildConfig.SIGNALING_URL.trim() }
            .trimEnd('/')
    val TURN_CREDENTIALS_URL: String get() = "$BASE_URL/turn-credentials"

    // Default namespace (Voice Rooms)
    val VOICE_URL: String get() = BASE_URL

    // Independent namespaces
    val CALL_URL: String get() = "$BASE_URL/call"
    val LIVE_URL: String get() = "$BASE_URL/live"
}

