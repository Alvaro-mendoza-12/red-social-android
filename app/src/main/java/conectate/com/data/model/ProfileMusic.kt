package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProfileMusicSummary(
    val profileId: String = "",
    val musicKey: String = "",
    val musicUrl: String = "",
    val presentation: String = "audio",
    val canEditPresentation: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
    val reaction: String? = null,
    val total: Int = 0,
)

internal val profileMusicReactions = linkedMapOf(
    "LIKE" to "👍", "LOVE" to "❤️", "FIRE" to "🔥", "HAHA" to "😂", "WOW" to "😮", "SAD" to "😢",
)

class ProfileMusicUnavailableException : Exception("La música ya no está disponible para esta cuenta")
class ProfileMusicChangedException : Exception("La música ha cambiado; vuelve a cargar el perfil")

internal fun ProfileMusicSummary.withReaction(value: String?): ProfileMusicSummary {
    if (reaction == value) return this
    val next = counts.toMutableMap()
    reaction?.let { next[it] = ((next[it] ?: 0) - 1).coerceAtLeast(0) }
    value?.let { next[it] = (next[it] ?: 0) + 1 }
    return copy(reaction = value, counts = next, total = next.values.sum())
}
