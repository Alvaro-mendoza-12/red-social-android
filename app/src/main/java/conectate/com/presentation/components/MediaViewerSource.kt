package conectate.com.presentation.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal fun shouldResolveMediaViewerSource(source: String, hasResolver: Boolean): Boolean {
    if (!hasResolver || source.isBlank()) return false
    val scheme = source.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme !in setOf("file", "content", "android.resource")
}

/** Public HTTP URLs also need the resolver: it may know an account-local offline copy. */
internal suspend fun resolveMediaViewerSource(
    source: String,
    resolver: (suspend (String) -> String?)?,
    timeoutMs: Long = 8_000L
): String? {
    if (!shouldResolveMediaViewerSource(source, resolver != null)) return source.takeIf(String::isNotBlank)
    return try {
        withTimeoutOrNull(timeoutMs) { resolver?.invoke(source)?.takeIf(String::isNotBlank) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
