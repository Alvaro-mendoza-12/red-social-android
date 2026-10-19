package conectate.com.presentation.messages

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin

internal enum class CloudMotionChoice { AUTO, ON, OFF }

internal fun cloudMotionRequested(choice: CloudMotionChoice, systemAnimationsEnabled: Boolean): Boolean =
    choice == CloudMotionChoice.ON || (choice == CloudMotionChoice.AUTO && systemAnimationsEnabled)

internal fun shouldMoveQuestionClouds(requested: Boolean, resumed: Boolean, answersOpen: Boolean): Boolean =
    requested && resumed && !answersOpen

internal data class CloudDrift(val x: Float, val y: Float)

/** Independent, bounded paths in the space reserved above and below the question. */
internal fun questionCloudDrift(progress: Float, slot: Int): CloudDrift {
    val angle = progress * Math.PI * 2 + slot * 2.1
    return CloudDrift((16 * cos(angle) + 2 * sin(angle * 2)).toFloat(), (10 * sin(angle) + 2 * cos(angle * 2)).toFloat())
}

@Composable
internal fun rememberQuestionCloudMotion(accountId: String?): Pair<CloudMotionChoice, (CloudMotionChoice) -> Unit> {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("question_cloud_motion", Context.MODE_PRIVATE) }
    val key = "choice:${accountId ?: "guest"}"
    fun read() = runCatching { CloudMotionChoice.valueOf(preferences.getString(key, "AUTO") ?: "AUTO") }.getOrDefault(CloudMotionChoice.AUTO)
    var choice by remember(key) { mutableStateOf(read()) }
    DisposableEffect(preferences, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed -> if (changed == key) choice = read() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return choice to { value -> choice = value; preferences.edit().putString(key, value.name).apply() }
}

/** An explicit local opt-in works even when Android's global animator scale is zero. */
@Composable
internal fun rememberQuestionCloudPhase(slot: Int, moving: Boolean): State<Float> {
    val phase = remember(slot) { mutableFloatStateOf(0f) }
    LaunchedEffect(slot, moving) {
        if (!moving) return@LaunchedEffect
        val period = (7000L + slot * 900L) * 1_000_000L
        var previous = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val delta = (now - previous).coerceIn(0L, 64_000_000L)
                previous = now
                phase.floatValue = (phase.floatValue + delta.toFloat() / period) % 1f
            }
        }
    }
    return phase
}
