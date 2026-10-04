package conectate.com.presentation.social

/** At most three durations, bounded by the actual video/photo and approved source. */
internal fun momentMusicClipDurations(maxClipMs: Long): List<Long> =
    if (maxClipMs <= 0L) emptyList()
    else listOf(15_000L, 30_000L, 60_000L).map { minOf(it, maxClipMs) }.distinct()
