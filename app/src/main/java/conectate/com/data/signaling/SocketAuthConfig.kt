package conectate.com.data.signaling

import io.socket.client.IO

fun IO.Options.attachBearerToken(accessToken: String?) {
    val token = accessToken?.trim().orEmpty()
    if (token.isBlank()) return
    extraHeaders = mapOf(
        "Authorization" to listOf("Bearer $token")
    )
    // FIX: Socket.IO v4+ prefers auth payload over headers for WebSockets
    auth = mapOf("token" to token)
}


