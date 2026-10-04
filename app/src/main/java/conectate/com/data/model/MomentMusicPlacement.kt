package conectate.com.data.model

/** Transport values are independent of the editor size and of the text overlay. */
fun normalizedMomentMusicPosition(value: Float): Float =
    value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .5f

fun normalizedMomentMusicScale(value: Float): Float =
    value.takeIf(Float::isFinite)?.coerceIn(.5f, 2f) ?: 1f

fun normalizedMomentMusicRotation(value: Float): Float =
    value.takeIf(Float::isFinite)?.let { ((it + 180f) % 360f + 360f) % 360f - 180f } ?: 0f
