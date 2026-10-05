package conectate.com.presentation.components

import android.net.Uri
import android.util.Patterns
import java.net.URI
import java.util.regex.Pattern

internal data class WebTextLink(val start: Int, val end: Int, val url: String)

private val webTextPattern: Pattern = Pattern.compile(Patterns.WEB_URL.pattern(), Pattern.CASE_INSENSITIVE)

/** Keeps sentence punctuation outside the link and normalizes bare web addresses. */
internal fun findWebTextLinks(text: String): List<WebTextLink> {
    val matcher = webTextPattern.matcher(text)
    return buildList {
        while (matcher.find()) {
            val start = matcher.start()
            if (start > 0 && text[start - 1] == '@') continue
            if (text.substring(0, start).endsWith("://")) continue
            var end = matcher.end()
            // Android's autolink expression can stop just before a closing parenthesis
            // that belongs to the URL (for example a wiki page name).
            while (end < text.length && text[end] in ")]}") {
                val closing = text[end]
                val opening = when (closing) { ')' -> '('; ']' -> '['; else -> '{' }
                val prefix = text.substring(start, end)
                if (prefix.count { it == opening } <= prefix.count { it == closing }) break
                end++
            }
            var displayed = text.substring(start, end).trimEnd('.', ',', ';', ':', '!', '?')
            for ((opening, closing) in listOf('(' to ')', '[' to ']', '{' to '}')) {
                while (displayed.endsWith(closing) && displayed.count { it == closing } > displayed.count { it == opening }) {
                    displayed = displayed.dropLast(1)
                }
            }
            if (displayed.isBlank()) continue
            if (displayed.contains("://") && !displayed.startsWith("http://", true) && !displayed.startsWith("https://", true)) continue
            val candidate = if (displayed.startsWith("http://", true) || displayed.startsWith("https://", true)) {
                displayed.substringBefore(':').lowercase() + displayed.substring(displayed.indexOf(':'))
            } else "https://$displayed"
            val uri = Uri.parse(candidate)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) continue
            add(WebTextLink(start, start + displayed.length, candidate))
        }
    }
}

/** A copied external URL must not become an internal route just by containing our hostname. */
internal fun postIdFromSharedLink(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") ||
        uri.host?.lowercase() !in setOf("conectate.com", "www.conectate.com")) return null
    val segments = uri.path.orEmpty().trim('/').split('/')
    if (segments.size != 2 || segments[0] != "post") return null
    return segments[1].takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,100}")) }
}
