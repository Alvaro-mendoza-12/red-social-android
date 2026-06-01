package conectate.com.data.webrtc

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException

class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener
) {
    private var socket: Socket? = null

    interface SignalingListener {
        fun onUserJoined(socketId: String, userId: String, name: String)
        fun onUserLeft(socketId: String)
        fun onOfferReceived(sender: String, offer: JSONObject, userId: String, name: String)
        fun onAnswerReceived(sender: String, answer: JSONObject)
        fun onIceCandidateReceived(sender: String, candidate: JSONObject)
        fun onCurrentParticipants(participants: List<String>)
    }

    init {
        try {
            val opts = IO.Options()
            opts.forceNew = true
            opts.reconnection = true
            opts.transports = arrayOf(io.socket.engineio.client.transports.WebSocket.NAME) // FIX: Bypass polling
            socket = IO.socket(serverUrl, opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("SignalingClient", "Connected to signaling server")
            }

            socket?.on("user-joined") { args ->
                var data = args[0] as JSONObject
                listener.onUserJoined(
                    data.getString("socketId"),
                    data.getString("userId"),
                    data.getString("name")
                )
            }

            socket?.on("user-left") { args ->
                val socketId = args[0] as String
                listener.onUserLeft(socketId)
            }

            socket?.on("offer") { args ->
                var data = args[0] as JSONObject
                listener.onOfferReceived(
                    data.getString("sender"),
                    data.getJSONObject("offer"),
                    data.getString("userId"),
                    data.getString("name")
                )
            }

            socket?.on("answer") { args ->
                var data = args[0] as JSONObject
                listener.onAnswerReceived(
                    data.getString("sender"),
                    data.getJSONObject("answer")
                )
            }

            socket?.on("ice-candidate") { args ->
                var data = args[0] as JSONObject
                listener.onIceCandidateReceived(
                    data.getString("sender"),
                    data.getJSONObject("candidate")
                )
            }

            socket?.on("current-participants") { args ->
                var data = args[0] as JSONArray
                val participants = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    participants.add(data.getString(i))
                }
                listener.onCurrentParticipants(participants)
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("SignalingClient", "Disconnected from signaling server")
            }

        } catch (e: URISyntaxException) {
            Log.e("SignalingClient", "Invalid server URL", e)
        }
    }

    fun connect() {
        socket?.connect()
    }

    fun disconnect() {
        socket?.disconnect()
    }

    fun joinRoom(roomId: String, userId: String, name: String) {
        var data = JSONObject()
        data.put("roomId", roomId)
        data.put("userId", userId)
        data.put("name", name)
        socket?.emit("join-room", data)
    }

    fun sendOffer(target: String, offer: JSONObject, userId: String, name: String) {
        var data = JSONObject()
        data.put("target", target)
        data.put("offer", offer)
        data.put("userId", userId)
        data.put("name", name)
        socket?.emit("offer", data)
    }

    fun sendAnswer(target: String, answer: JSONObject) {
        var data = JSONObject()
        data.put("target", target)
        data.put("answer", answer)
        socket?.emit("answer", data)
    }

    fun sendIceCandidate(target: String, candidate: JSONObject) {
        var data = JSONObject()
        data.put("target", target)
        data.put("candidate", candidate)
        socket?.emit("ice-candidate", data)
    }
}



