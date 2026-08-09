package conectate.com.presentation.wallet

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import conectate.com.data.local.prefs.MessagesPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Motivos originales sintetizados localmente; no contienen audio de terceros. */
internal enum class GiftSoundProfile {
    SEED_CHIME,
    WATER_DROP,
    PRISM_TWINKLE,
    SOFT_WINGS,
    BLOOM,
    LUNAR_ORBIT,
    HUMMINGBIRD_FLIGHT,
    NEBULA_CURRENT,
    AURORA_PAWS,
    COSMIC_WHALE,
    STAR_GARDEN,
    CRYSTAL_ORCHID,
    PRISM_LAUNCH,
    GUARDIAN_GALLOP,
    PHOENIX_RISE,
    STAR_TRAIN,
    CELESTIAL_DRAGON,
    FLOATING_ISLAND,
    CELESTIAL_WORLD,
    LIGHT_OPERA,
    AURORA_PALACE,
    TIME_KINGDOM,
    GALACTIC_ASCENT,
    AURORA_GENESIS
}

internal data class GiftSoundPlan(
    val durationMs: Int,
    val startHz: Float,
    val endHz: Float,
    val voices: Int,
    val pulseHz: Float,
    val sparkleRate: Float,
    val noiseMix: Float,
    val volume: Float
)

/**
 * Los regalos de mayor valor suman duración y capas, pero mantienen un techo
 * de volumen bajo para no competir con la voz del directo.
 */
internal fun giftSoundPlan(profile: GiftSoundProfile): GiftSoundPlan = when (profile) {
    GiftSoundProfile.SEED_CHIME -> GiftSoundPlan(420, 620f, 880f, 1, 0f, 5f, 0f, 0.13f)
    GiftSoundProfile.WATER_DROP -> GiftSoundPlan(480, 740f, 330f, 1, 0f, 2f, 0.01f, 0.13f)
    GiftSoundProfile.PRISM_TWINKLE -> GiftSoundPlan(520, 680f, 1_240f, 2, 0f, 9f, 0f, 0.13f)
    GiftSoundProfile.SOFT_WINGS -> GiftSoundPlan(560, 420f, 610f, 2, 11f, 4f, 0.02f, 0.12f)
    GiftSoundProfile.BLOOM -> GiftSoundPlan(620, 350f, 700f, 2, 1.8f, 6f, 0f, 0.13f)
    GiftSoundProfile.LUNAR_ORBIT -> GiftSoundPlan(700, 260f, 520f, 2, 1.2f, 4f, 0.01f, 0.13f)
    GiftSoundProfile.HUMMINGBIRD_FLIGHT -> GiftSoundPlan(820, 610f, 1_080f, 2, 29f, 12f, 0.02f, 0.12f)
    GiftSoundProfile.NEBULA_CURRENT -> GiftSoundPlan(860, 210f, 390f, 2, 2.8f, 4f, 0.04f, 0.13f)
    GiftSoundProfile.AURORA_PAWS -> GiftSoundPlan(900, 180f, 440f, 2, 6.4f, 5f, 0.03f, 0.13f)
    GiftSoundProfile.COSMIC_WHALE -> GiftSoundPlan(980, 120f, 300f, 3, 0.7f, 3f, 0.02f, 0.14f)
    GiftSoundProfile.STAR_GARDEN -> GiftSoundPlan(1_040, 320f, 820f, 3, 2.2f, 9f, 0.01f, 0.14f)
    GiftSoundProfile.CRYSTAL_ORCHID -> GiftSoundPlan(1_080, 510f, 1_020f, 3, 1.7f, 12f, 0f, 0.14f)
    GiftSoundProfile.PRISM_LAUNCH -> GiftSoundPlan(1_120, 170f, 920f, 3, 3.2f, 8f, 0.05f, 0.14f)
    GiftSoundProfile.GUARDIAN_GALLOP -> GiftSoundPlan(1_180, 145f, 480f, 3, 7.2f, 5f, 0.06f, 0.14f)
    GiftSoundProfile.PHOENIX_RISE -> GiftSoundPlan(1_260, 230f, 1_120f, 3, 2.4f, 10f, 0.05f, 0.15f)
    GiftSoundProfile.STAR_TRAIN -> GiftSoundPlan(2_000, 105f, 410f, 3, 5.4f, 6f, 0.07f, 0.15f)
    GiftSoundProfile.CELESTIAL_DRAGON -> GiftSoundPlan(2_600, 92f, 620f, 4, 1.6f, 8f, 0.07f, 0.15f)
    GiftSoundProfile.FLOATING_ISLAND -> GiftSoundPlan(2_717, 160f, 560f, 4, 0.8f, 8f, 0.03f, 0.15f)
    GiftSoundProfile.CELESTIAL_WORLD -> GiftSoundPlan(2_892, 125f, 680f, 4, 1.1f, 9f, 0.04f, 0.16f)
    GiftSoundProfile.LIGHT_OPERA -> GiftSoundPlan(3_126, 220f, 920f, 4, 2.1f, 11f, 0.02f, 0.16f)
    GiftSoundProfile.AURORA_PALACE -> GiftSoundPlan(3_360, 180f, 980f, 4, 1.8f, 12f, 0.03f, 0.16f)
    GiftSoundProfile.TIME_KINGDOM -> GiftSoundPlan(3_712, 150f, 760f, 5, 4.2f, 10f, 0.04f, 0.16f)
    GiftSoundProfile.GALACTIC_ASCENT -> GiftSoundPlan(4_180, 105f, 1_180f, 5, 2.6f, 14f, 0.05f, 0.17f)
    GiftSoundProfile.AURORA_GENESIS -> GiftSoundPlan(4_800, 72f, 1_320f, 5, 1.4f, 16f, 0.06f, 0.17f)
}

internal object GiftSoundEngine {
    private const val SAMPLE_RATE = 24_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pcmCache = java.util.concurrent.ConcurrentHashMap<GiftSoundProfile, ShortArray>()
    private val audioLock = Any()
    private val playbackGate = GiftSoundPlaybackGate<AudioTrack>()

    fun playConfirmed(context: Context, profile: GiftSoundProfile) {
        if (!MessagesPreferences.areSocialSoundsEnabled(context)) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) return

        val generation = playbackGate.currentGeneration()
        scope.launch {
            val plan = giftSoundPlan(profile)
            val samples = pcmCache.getOrPut(profile) {
                synthesizeGiftPcm(profile, plan, SAMPLE_RATE)
            }
            val player = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                    .build()
            }.getOrNull() ?: return@launch

            var startResult = PlayerStart.NOT_REGISTERED
            try {
                startResult = startPlayer(player, samples, plan, generation)
                if (startResult != PlayerStart.STARTED) return@launch
                delay(plan.durationMs.toLong() + 80L)
            } finally {
                synchronized(audioLock) {
                    val shouldRelease = when (startResult) {
                        PlayerStart.NOT_REGISTERED -> true
                        else -> playbackGate.unregister(player)
                    }
                    if (shouldRelease) releasePlayerLocked(player)
                }
            }
        }
    }

    /** Detiene audio al salir o mandar el directo a segundo plano. */
    fun stopAll() {
        synchronized(audioLock) {
            playbackGate.invalidateAndDrain().forEach(::releasePlayerLocked)
        }
    }

    /**
     * AudioTrack no es seguro frente a una liberación concurrente en todos los OEM.
     * Por eso write/play/release comparten un único candado y toda excepción del
     * framework se degrada a una celebración silenciosa, nunca a un crash.
     */
    private fun startPlayer(
        player: AudioTrack,
        samples: ShortArray,
        plan: GiftSoundPlan,
        generation: Int
    ): PlayerStart = synchronized(audioLock) {
        if (!playbackGate.tryRegister(generation, player)) return@synchronized PlayerStart.NOT_REGISTERED

        val started = runCatching {
            if (player.state != AudioTrack.STATE_INITIALIZED) return@runCatching false
            val written = player.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) return@runCatching false
            player.setVolume(plan.volume)
            player.play()
            true
        }.getOrDefault(false)

        if (started) PlayerStart.STARTED else PlayerStart.REGISTERED_NOT_STARTED
    }

    /** Se invoca solamente dentro de [audioLock]; repetirlo es seguro. */
    private fun releasePlayerLocked(player: AudioTrack) {
        runCatching { if (player.playState == AudioTrack.PLAYSTATE_PLAYING) player.stop() }
        runCatching { player.release() }
    }

    private enum class PlayerStart {
        NOT_REGISTERED,
        REGISTERED_NOT_STARTED,
        STARTED
    }
}

/**
 * Control puro y comprobable del ciclo de vida de audio. Una parada invalida
 * todos los inicios que estaban en curso y cada reproductor se puede liberar
 * más de una vez sin volver a quedar activo.
 */
internal class GiftSoundPlaybackGate<T : Any> {
    private var generation = 0
    private val active = LinkedHashSet<T>()

    @Synchronized
    fun currentGeneration(): Int = generation

    @Synchronized
    fun tryRegister(expectedGeneration: Int, player: T): Boolean {
        if (expectedGeneration != generation) return false
        active += player
        return true
    }

    @Synchronized
    fun unregister(player: T): Boolean = active.remove(player)

    @Synchronized
    fun invalidateAndDrain(): List<T> {
        generation += 1
        val drained = active.toList()
        active.clear()
        return drained
    }
}

/** PCM determinista, con envolvente suave y ruido pseudoaleatorio estable. */
internal fun synthesizeGiftPcm(
    profile: GiftSoundProfile,
    plan: GiftSoundPlan = giftSoundPlan(profile),
    sampleRate: Int = 24_000
): ShortArray {
    val sampleCount = (sampleRate * plan.durationMs / 1_000).coerceAtLeast(1)
    val durationSeconds = plan.durationMs / 1_000.0
    return ShortArray(sampleCount) { index ->
        val time = index.toDouble() / sampleRate
        val progress = (time / durationSeconds).coerceIn(0.0, 1.0)
        val frequency = plan.startHz + (plan.endHz - plan.startHz) * progress.toFloat()
        var harmonic = 0.0
        repeat(plan.voices) { voice ->
            val ratio = 1.0 + voice * 0.503
            val detune = 1.0 + ((profile.ordinal + voice) % 5 - 2) * 0.003
            harmonic += sin(2.0 * PI * frequency * ratio * detune * time + voice * 0.71) /
                (voice + 1.0)
        }
        harmonic /= (1.0 + (plan.voices - 1) * 0.42)

        val pulse = if (plan.pulseHz > 0f) {
            0.64 + 0.36 * (0.5 + 0.5 * sin(2.0 * PI * plan.pulseHz * time))
        } else 1.0
        val sparkle = if (plan.sparkleRate > 0f) {
            val cycle = (time * plan.sparkleRate) % 1.0
            exp(-cycle * 18.0) * sin(2.0 * PI * (1_350 + profile.ordinal * 17) * time) * 0.20
        } else 0.0
        val stableNoise = stableNoise(index, profile.ordinal) * plan.noiseMix
        val attack = (progress / 0.075).coerceIn(0.0, 1.0)
        val release = ((1.0 - progress) / 0.20).coerceIn(0.0, 1.0)
        val envelope = attack * release * (0.88 - progress * 0.22)
        val sample = ((harmonic * pulse + sparkle + stableNoise) * envelope * 0.72)
            .coerceIn(-1.0, 1.0)
        (sample * Short.MAX_VALUE).toInt().toShort()
    }
}

private fun stableNoise(index: Int, seed: Int): Double {
    val mixed = (index.toLong() * 1_103_515_245L + (seed + 17L) * 12_345L) and 0x7fffffffL
    return mixed.toDouble() / 1_073_741_823.5 - 1.0
}
