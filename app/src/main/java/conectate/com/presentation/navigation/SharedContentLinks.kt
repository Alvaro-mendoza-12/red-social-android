package conectate.com.presentation.navigation

import java.net.URI
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController

internal const val CONTENT_SHARE_ORIGIN = "https://red-social-android.vercel.app"
internal data class SharedContentDestination(val kind: String, val id: String) {
    val route: String get() = when (kind) {
        "post" -> Screen.PostDetail.createRoute(id)
        else -> Screen.UserProfile.createRoute(id)
    }
}

internal fun postShareLink(id: String): String {
    require(id.trim().length in 8..100) { "Publicación no válida" }
    return "$CONTENT_SHARE_ORIGIN/post/${requireContentId(id)}"
}
internal fun profileShareLink(id: String): String = "$CONTENT_SHARE_ORIGIN/u/${requireContentId(id)}"
private fun requireContentId(id: String): String = requireNotNull(safeExternalRouteId(id)) { "Contenido no válido" }

/** Exact origins and paths only. Shared text can never inject a navigation route. */
internal fun sharedContentDestination(url: String): SharedContentDestination? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    val path = uri.rawPath.orEmpty().trim('/').split('/')
    val values = when {
        scheme in setOf("https", "http") && host in setOf("conectate.com", "www.conectate.com", "red-social-android.vercel.app") -> {
            if (host == "red-social-android.vercel.app" && scheme != "https") return null
            when {
                path.size == 2 && path[0] in setOf("post", "profile", "u") -> path
                path.size == 4 && path.take(3) == listOf("app", "feed", "post") -> listOf("post", path[3])
                path.size == 3 && path.take(2) == listOf("app", "profile") -> listOf("profile", path[2])
                else -> return null
            }
        }
        scheme == "conectate" && host in setOf("post", "profile") && path.size == 1 -> listOf(host!!, path[0])
        else -> return null
    }
    val id = safeExternalRouteId(values[1]) ?: return null
    if (values[0] == "post" && id.length !in 8..100) return null
    return SharedContentDestination(if (values[0] == "post") "post" else "profile", id)
}

/** Graph-owned progress survives login/registration and Activity recreation. */
internal object PendingSharedContentDestination {
    private const val KIND = "shared_content_kind"
    private const val ID = "shared_content_id"
    fun remember(kind: String, id: String, state: SavedStateHandle) {
        if (kind !in setOf("post", "profile") || safeExternalRouteId(id) == null) return
        state[KIND] = kind; state[ID] = id.trim()
    }
    fun consume(state: SavedStateHandle): String? {
        val kind = state.remove<String>(KIND) ?: return null
        val id = state.remove<String>(ID) ?: return null
        return if (kind in setOf("post", "profile") && safeExternalRouteId(id) != null) SharedContentDestination(kind, id).route else null
    }
    fun consume(navController: NavController): String? = runCatching {
        consume(navController.getBackStackEntry(navController.graph.id).savedStateHandle)
    }.getOrNull()
}

internal fun NavController.openPendingSharedContent(): Boolean {
    val route = PendingSharedContentDestination.consume(this) ?: return false
    navigate(Screen.Home.createRoute()) { popUpTo(graph.id) { inclusive = false }; launchSingleTop = true }
    navigate(route) { launchSingleTop = true }
    return true
}
