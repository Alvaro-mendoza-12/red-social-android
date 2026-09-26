package conectate.com.data.music

object CommunityMusicPolicy {
    const val MIN_DURATION_MS = 5_000L
    const val MAX_DURATION_MS = 270_000L
    const val DURATION_HELP = "Entre 5 segundos y 4:30. Si dura más, elige el fragmento que quieres publicar."

    fun acceptsDuration(durationMs: Long): Boolean = durationMs in MIN_DURATION_MS..MAX_DURATION_MS
}
