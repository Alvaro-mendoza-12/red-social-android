import { useEffect, useRef, useState } from 'react'
import { StreamingAudio, StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { momentDuration, musicGain, unit } from './momentMusic'
import type { MomentItem } from './momentsData'
import './momentComposer.css'

/** One mounted instance per story: its clock and media never leak into the next one. */
export function MomentPlayback({ moment, paused, muted, onProgress, onComplete }: {
  moment: MomentItem; paused: boolean; muted: boolean
  onProgress: (progress: number) => void; onComplete: () => void
}) {
  const video = useRef<HTMLVideoElement>(null), audio = useRef<HTMLAudioElement>(null)
  const elapsed = useRef(0), completed = useRef(false)
  const [ready, setReady] = useState(false), [buffering, setBuffering] = useState(false)
  const [audioReady, setAudioReady] = useState(false), [audioFailed, setAudioFailed] = useState(false)
  const [failed, setFailed] = useState(false), [blocked, setBlocked] = useState(false), [attempt, setAttempt] = useState(0)
  const [duration, setDuration] = useState(momentDuration(moment.mediaType, moment.displayDurationMs))
  const sound = moment.music, separateSound = sound && !sound.embedded ? sound : undefined
  const needsAudio = Boolean(separateSound && !muted && !audioFailed)
  const hold = paused || !ready || buffering || failed || blocked || (needsAudio && !audioReady)

  useEffect(() => {
    const media = video.current, soundtrack = audio.current
    let current = true
    if (media) {
      media.muted = muted
      // Embedded native soundtracks are already mixed. Apply no second gain.
      media.volume = sound?.embedded ? 1 : unit(sound?.originalVolume ?? 1) * (needsAudio && sound?.duckOriginal ? .25 : 1)
      if (hold) media.pause()
      else void media.play().catch(() => { if (current) setBlocked(true) })
    }
    if (soundtrack) {
      soundtrack.muted = muted
      if (hold || !needsAudio) soundtrack.pause()
      else {
        const position = (separateSound!.startMs + elapsed.current) / 1000
        if (Math.abs(soundtrack.currentTime - position) > .25) soundtrack.currentTime = position
        soundtrack.volume = musicGain(separateSound!, elapsed.current)
        if (elapsed.current < separateSound!.durationMs) void soundtrack.play().catch(() => { if (current) setBlocked(true) })
      }
    }
    return () => { current = false; media?.pause(); soundtrack?.pause() }
  }, [hold, muted, needsAudio, separateSound, sound])

  useEffect(() => {
    if (hold) return
    let previous = performance.now()
    const timer = window.setInterval(() => {
      const now = performance.now()
      elapsed.current = video.current ? video.current.currentTime * 1000 : elapsed.current + now - previous
      previous = now
      onProgress(Math.min(100, elapsed.current / duration * 100))
      if (video.current && sound && !sound.embedded) video.current.volume = unit(sound.originalVolume) * (needsAudio && sound.duckOriginal && elapsed.current < sound.durationMs ? .25 : 1)
      if (audio.current && separateSound && needsAudio) {
        audio.current.volume = musicGain(separateSound, elapsed.current)
        if (elapsed.current >= separateSound.durationMs) audio.current.pause()
        else if (Math.abs(audio.current.currentTime * 1000 - separateSound.startMs - elapsed.current) > 350) {
          audio.current.currentTime = (separateSound.startMs + elapsed.current) / 1000
        }
      }
      if (elapsed.current >= duration && !completed.current) { completed.current = true; onComplete() }
    }, 50)
    return () => window.clearInterval(timer)
  }, [duration, hold, needsAudio, onComplete, onProgress, separateSound, sound])

  useEffect(() => {
    if ((ready && !buffering) || failed) return
    const timeout = window.setTimeout(() => setFailed(true), 20000)
    return () => window.clearTimeout(timeout)
  }, [ready, buffering, failed, attempt])
  useEffect(() => {
    if (!needsAudio || audioReady || audioFailed) return
    const timeout = window.setTimeout(() => setAudioFailed(true), 15000)
    return () => window.clearTimeout(timeout)
  }, [needsAudio, audioReady, audioFailed, attempt])

  function retry() {
    video.current?.pause(); audio.current?.pause()
    elapsed.current = 0; completed.current = false; onProgress(0)
    setReady(false); setBuffering(false); setAudioReady(false); setAudioFailed(false); setFailed(false); setBlocked(false); setAttempt(value => value + 1)
  }
  return <div className="moment-playback">
    {moment.mediaType === 'video' ? <StreamingVideo key={`video-${attempt}`} ref={video} className="story-viewer-media" src={moment.mediaUrl} playsInline muted={muted} preload="auto"
      onLoadedMetadata={event => { const actual = event.currentTarget.duration * 1000; if (Number.isFinite(actual) && actual > 0) setDuration(Math.min(momentDuration('video', moment.displayDurationMs), actual)) }}
      onCanPlay={() => { setReady(true); setBuffering(false) }} onWaiting={() => setBuffering(true)} onPlaying={() => setBuffering(false)}
      onEnded={() => { if (!completed.current) { completed.current = true; onComplete() } }} onError={() => setFailed(true)} />
      : <StreamingImage key={`image-${attempt}`} className="story-viewer-media" src={moment.mediaUrl} alt={moment.caption || 'Momento'} onLoad={() => setReady(true)} onError={() => setFailed(true)} />}
    {separateSound ? <StreamingAudio key={`audio-${attempt}`} ref={audio} src={separateSound.audioUrl} preload="auto" muted={muted}
      onLoadedMetadata={() => { if (audio.current) audio.current.currentTime = (separateSound.startMs + elapsed.current) / 1000 }}
      onCanPlay={() => setAudioReady(true)} onWaiting={() => setAudioReady(false)} onError={() => setAudioFailed(true)} /> : null}
    {moment.overlay ? <span className="moment-text-overlay" style={{ left: `${moment.overlay.x * 100}%`, top: `${moment.overlay.y * 100}%`, color: moment.overlay.color, fontFamily: moment.overlay.font === 'mono' ? 'monospace' : moment.overlay.font === 'serif' ? 'serif' : 'sans-serif' }}>{moment.overlay.text}</span> : null}
    {sound && sound.presentation !== 'audio' ? <div className={`moment-sound-credit ${sound.presentation === 'card' ? 'card' : 'label'}`}><span aria-hidden="true">♫</span><div><strong>{sound.title}</strong>{sound.artist ? <small>{sound.artist}</small> : null}</div></div> : null}
    {audioFailed ? <p className="moment-audio-notice" role="status">No se pudo cargar la música. Puedes seguir viendo el momento.</p> : null}
    {(!ready || buffering || (needsAudio && !audioReady)) && !failed ? <div className="story-viewer-loading" role="status">Cargando…</div> : null}
    {failed || blocked ? <div className="moment-playback-action" onPointerDown={event => event.stopPropagation()} onPointerUp={event => event.stopPropagation()}>
      <p>{failed ? 'No se pudo cargar este momento.' : 'Pulsa para reproducir el momento.'}</p>
      <button type="button" className="primary-btn" onClick={() => { if (failed) retry(); else { setBlocked(false); void video.current?.play().catch(() => {}); if (needsAudio) void audio.current?.play().catch(() => {}) } }}>{failed ? 'Reintentar' : 'Reproducir'}</button>
    </div> : null}
  </div>
}
