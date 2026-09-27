package conectate.com.data.signaling

import conectate.com.core.network.PublicBackendDns
import io.socket.client.IO
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val signalingClient = OkHttpClient.Builder().dns(PublicBackendDns).build()

fun IO.Options.attachBearerToken(accessToken: String?) {
    val token = accessToken?.trim().orEmpty()
    if (token.isBlank()) return
    extraHeaders = mapOf(
        "Authorization" to listOf("Bearer $token")
    )
    // FIX: Socket.IO v4+ prefers auth payload over headers for WebSockets
    auth = mapOf("token" to token)
}

fun IO.Options.attachSignalingAffinity(scope: String, rawScopeId: String) {
    require(scope in setOf("voice", "call", "live")) { "Invalid signaling scope" }
    val scopeId = rawScopeId.trim()
    require(scopeId.isNotBlank() && scopeId.length <= 160) { "Invalid signaling scope id" }
    val value = URLEncoder.encode(
        "$scope:$scopeId",
        StandardCharsets.UTF_8.toString()
    )
    query = "affinity=$value"
    callFactory = signalingClient
    webSocketFactory = signalingClient
}


