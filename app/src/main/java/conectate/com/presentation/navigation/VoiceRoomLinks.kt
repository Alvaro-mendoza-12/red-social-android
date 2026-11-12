package conectate.com.presentation.navigation

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController

internal const val VOICE_ROOM_SHARE_ORIGIN = conectate.com.data.model.PublicWebDomain.activeOrigin

internal fun voiceRoomShareLink(roomId: String): String? = roomId.trim()
    .takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
    ?.let { "$VOICE_ROOM_SHARE_ORIGIN/voice/$it" }

internal fun voiceRoomFromLink(uri: Uri): String? {
    if (uri.userInfo != null || uri.port != -1) return null
    val segments = uri.pathSegments
    val value = when {
        uri.scheme == "conectate" && uri.host == "voice" && segments.size == 1 -> segments[0]
        uri.scheme == "https" && uri.host in (conectate.com.data.model.PublicWebDomain.httpsHosts + setOf("conectate.com", "www.conectate.com")) &&
            segments.size == 2 && segments[0] == "voice" -> segments[1]
        else -> null
    }
    return value?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
}

/** The root navigation entry survives login, registration and configuration changes. */
internal object PendingVoiceRoomDestination {
    private const val KEY = "pendingVoiceRoomId"
    fun remember(id: String, state: SavedStateHandle) {
        if (voiceRoomShareLink(id) != null) state[KEY] = id.trim()
    }
    fun consume(state: SavedStateHandle): String? = state.remove<String>(KEY)
        ?.takeIf { voiceRoomShareLink(it) != null }?.let(Screen.RoomDetail::createRoute)
    fun consume(navController: NavController): String? = runCatching {
        consume(navController.getBackStackEntry(navController.graph.id).savedStateHandle)
    }.getOrNull()
}
