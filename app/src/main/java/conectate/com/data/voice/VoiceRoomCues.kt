package conectate.com.data.voice

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import conectate.com.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VoiceCueSettings(val presence: Boolean = true, val microphone: Boolean = true, val volume: Float = .35f)

/** Local playback shares the existing communication route, without acquiring audio focus. */
class VoiceRoomCues(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("voice-room-cues", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(VoiceCueSettings())
    val settings = _settings.asStateFlow()
    private var account = ""
    private var room = ""
    private var policy: VoiceRoomCuePolicy? = null
    private var player: CueSoundPool? = null
    private var departureJob: Job? = null

    @Synchronized fun start(accountId: String, roomId: String) {
        if (account == accountId && room == roomId && policy != null) return
        stop(false); account = accountId; room = roomId
        _settings.value = VoiceCueSettings(prefs.getBoolean("$account:presence", true), prefs.getBoolean("$account:microphone", true), prefs.getFloat("$account:volume", .35f).coerceIn(0f, 1f))
        policy = VoiceRoomCuePolicy(accountId)
        player = runCatching { CueSoundPool(context) }.getOrNull()
    }
    @Synchronized fun updateSettings(value: VoiceCueSettings) {
        if (account.isBlank()) return
        val safe = value.copy(volume = if (value.volume.isFinite()) value.volume.coerceIn(0f, 1f) else .35f)
        _settings.value = safe
        prefs.edit().putBoolean("$account:presence", safe.presence).putBoolean("$account:microphone", safe.microphone).putFloat("$account:volume", safe.volume).apply()
    }
    @Synchronized fun connected(muted: Boolean) = play(policy?.connect(muted).orEmpty())
    @Synchronized fun baseline(peers: List<CueParticipant>) { policy?.baseline(peers) }
    @Synchronized fun joined(peer: CueParticipant) = play(policy?.join(peer).orEmpty())
    @Synchronized fun muted(id: String, muted: Boolean) = play(policy?.mute(id, muted).orEmpty())
    @Synchronized fun left(socketId: String) {
        policy?.leave(socketId, SystemClock.elapsedRealtime())
        // One bounded drain handles a burst of departures; no queue of audio clips.
        departureJob?.cancel()
        departureJob = scope.launch { delay(725); synchronized(this@VoiceRoomCues) { play(policy?.drain(SystemClock.elapsedRealtime()).orEmpty()) } }
    }
    @Synchronized fun reconnecting() { departureJob?.cancel(); policy?.suspend() }
    @Synchronized fun stop(audible: Boolean) {
        departureJob?.cancel(); departureJob = null
        if (audible) play(policy?.leaveSelf().orEmpty()) else policy?.suspend()
        val old = player; player = null; policy = null; room = ""
        if (old != null) scope.launch { delay(if (audible) 350 else 0); old.close() }
    }
    private fun play(cues: List<VoiceCue>) { cues.firstOrNull()?.let { player?.play(it, _settings.value) } }
}

private class CueSoundPool(private val context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ready = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val sounds: Map<VoiceCue, Int>
    private var lastAt = Long.MIN_VALUE
    private var closed = false
    init {
        pool.setOnLoadCompleteListener { _, sample, status -> if (status == 0) ready.add(sample) }
        sounds = mapOf(VoiceCue.JOIN to R.raw.voice_join, VoiceCue.LEAVE to R.raw.voice_leave, VoiceCue.MUTE to R.raw.voice_mute, VoiceCue.UNMUTE to R.raw.voice_unmute).mapValues { pool.load(context, it.value, 1) }
    }
    @Synchronized fun play(cue: VoiceCue, settings: VoiceCueSettings) {
        if (closed || settings.volume <= 0f || if (cue == VoiceCue.JOIN || cue == VoiceCue.LEAVE) !settings.presence else !settings.microphone) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || notifications?.currentInterruptionFilter in listOf(NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALARMS)) return
        val now = SystemClock.elapsedRealtime()
        if (lastAt != Long.MIN_VALUE && now - lastAt < 300) return
        val sample = sounds[cue] ?: return
        if (sample !in ready) return
        lastAt = now
        runCatching { pool.play(sample, settings.volume, settings.volume, 1, 0, 1f) }
    }
    @Synchronized fun close() { if (!closed) { closed = true; pool.release(); ready.clear() } }
}
