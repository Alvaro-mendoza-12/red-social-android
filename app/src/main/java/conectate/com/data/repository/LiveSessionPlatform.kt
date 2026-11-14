package conectate.com.data.repository

import android.content.Context
import conectate.com.data.webrtc.SignalingEvents
import conectate.com.data.webrtc.WebRTCManager
import io.socket.client.IO
import io.socket.client.Socket
import javax.inject.Inject

/** Construction boundary; lifecycle policy remains in the real LIVE repository. */
interface LiveSessionPlatform {
    fun socket(endpoint: String, options: IO.Options): Socket
    fun media(
        context: Context,
        signalingEvents: SignalingEvents,
        accessTokenProvider: () -> String?,
        failureReporter: (String, Throwable) -> Unit
    ): WebRTCManager
}

class DefaultLiveSessionPlatform @Inject constructor() : LiveSessionPlatform {
    override fun socket(endpoint: String, options: IO.Options): Socket = IO.socket(endpoint, options)
    override fun media(
        context: Context,
        signalingEvents: SignalingEvents,
        accessTokenProvider: () -> String?,
        failureReporter: (String, Throwable) -> Unit
    ): WebRTCManager = WebRTCManager(context, signalingEvents, accessTokenProvider, failureReporter)
}
