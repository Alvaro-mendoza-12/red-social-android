package conectate.com.presentation.navigation

import conectate.com.data.model.PublicWebDomain
import java.net.URI

private val liveSharedId = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")

internal fun liveShareLink(id: String): String {
    require(liveSharedId.matches(id.trim())) { "Directo no válido" }
    return "${PublicWebDomain.activeOrigin}/app/live/${id.trim()}"
}

internal fun liveRouteFromShareLink(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host?.lowercase() !in PublicWebDomain.httpsHosts ||
        uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null) return null
    val parts = uri.rawPath.orEmpty().split('/')
    if (parts.size != 4 || parts.take(3) != listOf("", "app", "live")) return null
    return parts[3].takeIf(liveSharedId::matches)?.let(::resolveLiveStartedRoute)
}
