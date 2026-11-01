package conectate.com.presentation.social

import java.net.URI
import java.util.Locale

internal enum class ChallengeMediaKind { IMAGE, VIDEO, AUDIO }

internal fun challengeMediaKind(challengeType: String, source: String): ChallengeMediaKind {
    if (challengeType.equals("audio", ignoreCase = true)) return ChallengeMediaKind.AUDIO
    val path = (runCatching { URI(source.trim()).path }.getOrNull()
        ?: source.substringBefore('?').substringBefore('#')).lowercase(Locale.ROOT)
    val video = listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".3gp", ".m3u8").any(path::endsWith) ||
        (path.contains("/videos/") && !path.substringAfterLast('/').contains('.'))
    return if (video) ChallengeMediaKind.VIDEO else ChallengeMediaKind.IMAGE
}
