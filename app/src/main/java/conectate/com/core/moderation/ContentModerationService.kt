package conectate.com.core.moderation

import conectate.com.core.observability.AppMetrics
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class ModerationContext {
    CHAT_MESSAGE,
    GROUP_MESSAGE,
    COMMUNITY_MESSAGE,
    POST_CONTENT,
    COMMENT_CONTENT,
    REPORT_REASON,
    REPORT_DESCRIPTION
}

enum class ModerationVerdict {
    ALLOW,
    SANITIZE,
    BLOCK
}

data class ModerationResult(
    val verdict: ModerationVerdict,
    val sanitizedText: String,
    val flags: Set<String> = emptySet()
)

interface ContentModerationService {
    fun moderate(
        text: String,
        context: ModerationContext
    ): ModerationResult
}

@Singleton
class ModerationNoticeCenter @Inject constructor() {
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    fun publish(message: String) {
        _notices.tryEmit(message)
    }
}

@Singleton
class SimpleContentModerationService @Inject constructor(
    private val appMetrics: AppMetrics,
    private val noticeCenter: ModerationNoticeCenter
) : ContentModerationService {

    private val blockedTerms = setOf(
        "kill yourself",
        "nude minors",
        "terror guide",
        "cp link",
        "bomb tutorial",
        "credit card dump",
        "ssn leak",
        "mátate",
        "matate",
        "suicídate",
        "suicidate",
        "voy a matarte",
        "pornografia infantil"
    )

    private val maskedTerms = setOf(
        "idiot",
        "stupid",
        "imbecil",
        "pendejo",
        "mierda",
        "cabron",
        "puta",
        "puto",
        "maricon",
        "estupido",
        "idiota",
        "malparido",
        "hijueputa",
        "hijo de puta",
        "wtf"
    )

    private val suspiciousDomains = setOf(
        "grabify",
        "iplogger",
        "tinyurl.com",
        "bit.ly",
        "cutt.ly",
        "shorturl"
    )

    private val phoneRegex = "(?<!\\d)(\\+?\\d[\\d\\s\\-]{6,}\\d)(?!\\d)".toRegex()
    private val emailRegex = "\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b".toRegex()
    private val blockedPatterns = blockedTerms.map(::obfuscatedTermRegex)
    private val maskedPatterns = maskedTerms.map(::obfuscatedTermRegex)

    override fun moderate(text: String, context: ModerationContext): ModerationResult {
        val normalized = stripInvisibleChars(text).trim()
        if (normalized.isBlank()) {
            return emitResult(
                context = context,
                verdict = ModerationVerdict.ALLOW,
                sanitizedText = normalized
            )
        }

        if (blockedPatterns.any { it.containsMatchIn(normalized) }) {
            return emitResult(
                context = context,
                verdict = ModerationVerdict.BLOCK,
                sanitizedText = "",
                flags = setOf("blocked_term")
            )
        }

        var links = "(https?://\\S+)".toRegex().findAll(normalized).map { it.value.lowercase() }.toList()
        val excessiveLinks = links.size >= 4
        if (excessiveLinks) {
            return emitResult(
                context = context,
                verdict = ModerationVerdict.BLOCK,
                sanitizedText = "",
                flags = setOf("link_spam")
            )
        }
        if (links.any { link -> suspiciousDomains.any { domain -> link.contains(domain) } }) {
            return emitResult(
                context = context,
                verdict = ModerationVerdict.BLOCK,
                sanitizedText = "",
                flags = setOf("suspicious_link")
            )
        }

        val containsControlChars = normalized.any { it.code < 32 && it != '\n' && it != '\t' }
        val repeatedChars = "(.)\\1{7,}".toRegex().containsMatchIn(normalized)
        val heavyUppercase = normalized.length >= 18 && uppercaseRatio(normalized) > 0.75
        val mentionSpam = "@\\w+".toRegex().findAll(normalized).count() >= 12
        val repeatedWordSpam = "\\b(\\p{L}{2,})\\b(?:\\s+\\1){5,}".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(normalized)

        if (mentionSpam || repeatedWordSpam) {
            return emitResult(
                context = context,
                verdict = ModerationVerdict.BLOCK,
                sanitizedText = "",
                flags = setOf("pattern_spam")
            )
        }

        var sanitized = normalized
        var flags = mutableSetOf<String>()

        if (containsControlChars) {
            sanitized = sanitized.filter { it.code >= 32 || it == '\n' || it == '\t' }
            flags += "control_chars_removed"
        }

        if (repeatedChars) {
            sanitized = "(.)\\1{4,}".toRegex().replace(sanitized) { match ->
                match.value.take(4)
            }
            flags += "repeat_spam"
        }

        if (heavyUppercase) {
            sanitized = sanitized.lowercase()
            flags += "caps_spam"
        }

        var masked = sanitized
        maskedPatterns.forEach { regex ->
            if (regex.containsMatchIn(masked)) {
                masked = regex.replace(masked) { "***" }
                flags += "masked_term"
            }
        }

        val isPublicSurface = context == ModerationContext.POST_CONTENT ||
            context == ModerationContext.COMMENT_CONTENT ||
            context == ModerationContext.COMMUNITY_MESSAGE
        if (isPublicSurface) {
            val redactedPhone = phoneRegex.replace(masked) { match ->
                flags += "contact_redacted"
                maskToken(match.value)
            }
            masked = emailRegex.replace(redactedPhone) { match ->
                flags += "contact_redacted"
                maskToken(match.value)
            }
        }

        val maxLength = maxLengthFor(context)
        if (masked.length > maxLength) {
            masked = masked.take(maxLength)
            flags += "trimmed_length"
        }

        var verdict = if (flags.isEmpty()) {
            ModerationVerdict.ALLOW
        } else {
            ModerationVerdict.SANITIZE
        }
        return emitResult(
            context = context,
            verdict = verdict,
            sanitizedText = masked,
            flags = flags
        )
    }

    private fun maxLengthFor(context: ModerationContext): Int {
        return when (context) {
            ModerationContext.REPORT_REASON -> 120
            ModerationContext.REPORT_DESCRIPTION -> 2000
            ModerationContext.COMMENT_CONTENT -> 1500
            else -> 4000
        }
    }

    private fun uppercaseRatio(text: String): Double {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.0
        val uppercase = letters.count { it.isUpperCase() }
        return uppercase.toDouble() / letters.length.toDouble()
    }

    private fun stripInvisibleChars(text: String): String {
        return text
            .replace("[\\u200B\\u200C\\u200D\\uFEFF]".toRegex(), "")
            .replace("\\r\\n".toRegex(), "\n")
    }

    /**
     * Detects common evasion without treating harmless substrings as insults.
     * It accepts repeated letters, separators and leetspeak (for example
     * `p.u.t.4`, `pvvta` or `1mb3c1l`) while retaining word boundaries.
     */
    private fun obfuscatedTermRegex(term: String): Regex {
        val canonical = java.text.Normalizer.normalize(term.lowercase(), java.text.Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .filter(Char::isLetterOrDigit)
        val characters = canonical.map { character ->
            when (character) {
                'a' -> "[aáàäâ4@]"
                'b' -> "[b8]"
                'e' -> "[eéèëê3]"
                'g' -> "[g9]"
                'i' -> "[iíìïî1!|]"
                'o' -> "[oóòöô0]"
                's' -> "[s5\$]"
                't' -> "[t7+]"
                'u' -> "[uúùüûv]"
                else -> Regex.escape(character.toString())
            } + "+"
        }
        val body = characters.joinToString("[\\W_]*")
        return Regex("(?<![\\p{L}\\p{N}])$body(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    private fun maskToken(token: String): String {
        if (token.length <= 4) return "***"
        val prefix = token.take(2)
        val suffix = token.takeLast(2)
        return "$prefix***$suffix"
    }

    private fun emitResult(
        context: ModerationContext,
        verdict: ModerationVerdict,
        sanitizedText: String,
        flags: Set<String> = emptySet()
    ): ModerationResult {
        val contextLabel = context.name.lowercase()
        val verdictLabel = verdict.name.lowercase()
        appMetrics.incrementCounter(
            metric = "moderation_decision",
            attributes = mapOf(
                "context" to contextLabel,
                "verdict" to verdictLabel
            )
        )
        if (verdict == ModerationVerdict.BLOCK) {
            appMetrics.recordFailure(
                metric = "moderation_block",
                attributes = mapOf(
                    "context" to contextLabel,
                    "flags" to flags.sorted().joinToString(",")
                )
            )
        }
        val isUserAuthoredPublicContent = context == ModerationContext.COMMENT_CONTENT ||
            context == ModerationContext.POST_CONTENT ||
            context == ModerationContext.COMMUNITY_MESSAGE
        if (isUserAuthoredPublicContent) {
            when (verdict) {
                ModerationVerdict.BLOCK -> noticeCenter.publish(
                    "No publicamos ese contenido porque incumple las normas de convivencia."
                )
                ModerationVerdict.SANITIZE -> noticeCenter.publish(
                    "Ocultamos lenguaje ofensivo o datos sensibles antes de publicar."
                )
                ModerationVerdict.ALLOW -> Unit
            }
        }
        return ModerationResult(
            verdict = verdict,
            sanitizedText = sanitizedText,
            flags = flags
        )
    }
}



