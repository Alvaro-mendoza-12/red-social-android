import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { StreamingAudio, StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { useAuth } from '../../core/auth/AuthProvider'
import { friendlyError } from '../../core/utils'
import { publishMoment } from './momentsData'
import { momentDuration, momentFileError, musicGain, musicRange, searchMomentMusic, type MomentMusicSelection, type MomentOverlay, type MomentTrack } from './momentMusic'
import { localAudioDuration } from './localMomentAudio'
import './momentComposer.css'

function useFileSource(file: File | null) {
  const [ready, setReady] = useState<{ file: File; url: string } | null>(null)
  useEffect(() => {
    if (!file) return
    const url = URL.createObjectURL(file)
    // Object URLs are external resources; publish the URL only after allocating it.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setReady({ file, url })
    return () => URL.revokeObjectURL(url)
  }, [file])
  return ready?.file === file ? ready.url : ''
}
function clock(ms: number) { const seconds = Math.floor(Math.max(0, ms) / 1000); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` }
function MusicSearch({ onSelect }: { onSelect: (track: MomentTrack) => void }) {
  const [query, setQuery] = useState(''), [tracks, setTracks] = useState<MomentTrack[]>([]), [cursor, setCursor] = useState('')
  const [busy, setBusy] = useState(true), [error, setError] = useState(false), [reload, setReload] = useState(0)
  const generation = useRef(0), moreRequest = useRef<AbortController | null>(null)
  useEffect(() => {
    const controller = new AbortController(); generation.current++; moreRequest.current?.abort(); setTracks([]); setCursor(''); setBusy(true); setError(false)
    const timer = window.setTimeout(() => { void searchMomentMusic(query, undefined, controller.signal).then(result => {
      if (!controller.signal.aborted) { setTracks(result.tracks); setCursor(result.nextCursor) }
    }).catch(() => { if (!controller.signal.aborted) setError(true) }).finally(() => { if (!controller.signal.aborted) setBusy(false) }) }, 250)
    return () => { clearTimeout(timer); controller.abort(); moreRequest.current?.abort(); generation.current++ }
  }, [query, reload])
  async function more() {
    if (busy || !cursor) return
    const expected = generation.current, controller = new AbortController(); moreRequest.current = controller; setBusy(true); setError(false)
    try { const result = await searchMomentMusic(query, cursor, controller.signal); if (expected === generation.current && !controller.signal.aborted) { setTracks(old => [...new Map([...old, ...result.tracks].map(track => [track.id, track])).values()]); setCursor(result.nextCursor) } }
    catch { if (expected === generation.current && !controller.signal.aborted) setError(true) } finally { if (expected === generation.current && !controller.signal.aborted) setBusy(false) }
  }
  return <section className="moment-music-search" aria-label="Música de comunidad">
    <label>Buscar música de comunidad<input className="text-input" value={query} onChange={event => setQuery(event.target.value)} placeholder="Canción o artista" /></label>
    {busy && !tracks.length ? <p role="status">Buscando música…</p> : null}
    {error ? <p role="alert">No pudimos cargar la música. <button type="button" className="ghost-btn" onClick={() => setReload(value => value + 1)}>Reintentar</button></p> : null}
    {!busy && !error && !tracks.length ? <p>No hay canciones disponibles para usar en momentos con esta búsqueda.</p> : null}
    <div className="moment-music-results">{tracks.map(track => <button className="moment-track" type="button" key={track.id} onClick={() => onSelect(track)}><strong>{track.title}</strong><span>{track.artist} · {clock(track.durationMs)}</span></button>)}</div>
    {cursor ? <button className="ghost-btn" type="button" disabled={busy} onClick={() => void more()}>Ver más canciones</button> : null}
  </section>
}
function ClipPreview({ selection, durationMs }: { selection: MomentMusicSelection; durationMs: number }) {
  const audio = useRef<HTMLAudioElement>(null), [playing, setPlaying] = useState(false), [failed, setFailed] = useState(false)
  const local = useFileSource(selection.track.localFile || null), range = musicRange(selection.track.durationMs, durationMs, selection.startMs)
  useEffect(() => { if (audio.current && audio.current.readyState >= 1) audio.current.currentTime = range.startMs / 1000 }, [range.startMs])
  useEffect(() => { if (audio.current) audio.current.volume = selection.volume }, [selection.volume])
  useEffect(() => { const element = audio.current; return () => element?.pause() }, [])
  async function toggle() {
    const element = audio.current; if (!element) return
    if (playing) { element.pause(); return }
    setFailed(false); if (element.readyState >= 1) element.currentTime = range.startMs / 1000
    try { await element.play() } catch { setFailed(true) }
  }
  return <div><StreamingAudio ref={audio} src={local || selection.track.audioUrl} preload="metadata"
    onLoadedMetadata={() => { if (audio.current) { audio.current.currentTime = range.startMs / 1000; audio.current.volume = selection.volume } }}
    onTimeUpdate={() => { const element = audio.current; if (!element) return; if (element.currentTime >= range.endMs / 1000) element.currentTime = range.startMs / 1000; element.volume = musicGain({ ...selection, durationMs: range.durationMs }, Math.max(0, element.currentTime * 1000 - range.startMs)) }}
    onEnded={() => { const element = audio.current; if (element) { element.currentTime = range.startMs / 1000; void element.play().catch(() => setFailed(true)) } }}
    onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onError={() => { setFailed(true); setPlaying(false) }} />
    <button className="ghost-btn" type="button" onClick={() => void toggle()}>{playing ? 'Pausar fragmento' : 'Escuchar fragmento'}</button>
    {failed ? <p role="alert">No se pudo reproducir el audio. Vuelve a intentarlo o elige otra canción.</p> : null}</div>
}

export function MomentComposer({ onClose, onPublished }: { onClose: () => void; onPublished: () => void }) {
  const { user } = useAuth(), dialog = useRef<HTMLElement>(null)
  const audioRead = useRef<AbortController | null>(null)
  const [file, setFile] = useState<File | null>(null), source = useFileSource(file)
  const [videoDuration, setVideoDuration] = useState(0), [caption, setCaption] = useState(''), [durationHours, setDurationHours] = useState(24)
  const [visibility, setVisibility] = useState('all'), [replies, setReplies] = useState(true), [searching, setSearching] = useState(false)
  const [music, setMusic] = useState<MomentMusicSelection>(), [saving, setSaving] = useState(false), [error, setError] = useState('')
  const [localBusy, setLocalBusy] = useState(false), [overlay, setOverlay] = useState<MomentOverlay>({ text: '', color: '#ffffff', font: 'sans', x: .5, y: .38 })
  const video = file?.type.startsWith('video/') === true, durationMs = momentDuration(video ? 'video' : 'image', videoDuration)
  const hasDraft = Boolean(file || caption || music || localBusy || overlay.text || durationHours !== 24 || visibility !== 'all' || !replies)
  const requestClose = useCallback(() => {
    if (saving) return
    if (!hasDraft || window.confirm('¿Descartar este momento? Se perderán los cambios que no has publicado.')) onClose()
  }, [hasDraft, onClose, saving])
  useEffect(() => {
    if (!hasDraft && !saving) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [hasDraft, saving])
  useEffect(() => { const previous = document.activeElement as HTMLElement | null; dialog.current?.focus(); return () => previous?.focus() }, [])
  useEffect(() => () => audioRead.current?.abort(), [])
  useEffect(() => { const close = (event: KeyboardEvent) => { if (event.key === 'Escape') { event.preventDefault(); requestClose() } }; window.addEventListener('keydown', close); return () => window.removeEventListener('keydown', close) }, [requestClose])
  function chooseTrack(track: MomentTrack) { audioRead.current?.abort(); audioRead.current = null; setLocalBusy(false); setMusic({ track, startMs: 0, volume: 1, originalVolume: 1, fadeInMs: 0, fadeOutMs: 0, duckOriginal: false, presentation: 'card' }); setSearching(false) }
  async function chooseAudio(audio: File | undefined) {
    if (!audio) return
    if (!audio.type.startsWith('audio/') || audio.size > 80 * 1024 * 1024) { setError('Elige un archivo de audio de hasta 80 MB.'); return }
    audioRead.current?.abort()
    const controller = new AbortController(); audioRead.current = controller
    setLocalBusy(true); setError('')
    try { const duration = await localAudioDuration(audio, controller.signal); if (!controller.signal.aborted) chooseTrack({ id: `device:${crypto.randomUUID()}`, title: audio.name.replace(/\.[^.]+$/, ''), artist: 'Mi audio', audioUrl: '', source: 'novvin_moment_local', durationMs: duration, localFile: audio }) }
    catch (caught) { if (!controller.signal.aborted) setError(friendlyError(caught, 'No pudimos abrir el audio.')) }
    finally { if (audioRead.current === controller) { audioRead.current = null; setLocalBusy(false) } }
  }
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!file || !user || saving || localBusy || (video && !videoDuration)) return
    setSaving(true); setError('')
    try { await publishMoment({ file, caption: caption.trim(), durationHours, visibility, allowReplies: replies, displayDurationMs: durationMs, music, overlay: overlay.text.trim() ? overlay : undefined, expectedAccountId: user.id }); onPublished() }
    catch (caught) { setError(friendlyError(caught, 'No se pudo publicar. Tu selección sigue aquí para reintentarlo.')) } finally { setSaving(false) }
  }
  const range = music ? musicRange(music.track.durationMs, durationMs, music.startMs) : null
  function changeMusic(values: Partial<MomentMusicSelection>) { setMusic(current => current ? { ...current, ...values } : undefined) }
  return <div className="app-dialog-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) requestClose() }}>
    <article ref={dialog} tabIndex={-1} className="surface-card moment-editor" role="dialog" aria-modal="true" aria-label="Publicar momento" onKeyDown={event => {
      if (event.key !== 'Tab') return
      const controls = Array.from(dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled):not([hidden]),textarea:not(:disabled),select:not(:disabled),summary') || []).filter(element => element.getClientRects().length > 0)
      const first = controls[0], last = controls[controls.length - 1]
      if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.current)) { event.preventDefault(); last?.focus() }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.current)) { event.preventDefault(); first?.focus() }
    }}>
      <div className="row-between"><h3>Crear momento</h3><button className="ghost-btn" type="button" disabled={saving} onClick={requestClose}>Cerrar</button></div>
      <form onSubmit={event => void submit(event)}><fieldset disabled={saving}>
        <label>Foto o video<input type="file" accept="image/jpeg,image/png,image/webp,video/mp4" onChange={event => { const next = event.target.files?.[0]; event.target.value = ''; if (!next) return; const invalid = momentFileError(next); if (invalid) { setError(invalid); return } setFile(next); setVideoDuration(0); setError('') }} /></label>
        {source ? <div className="moment-editor-preview">{video ? <StreamingVideo key={source} src={source} controls muted playsInline onError={() => { setVideoDuration(0); setError('No se pudo abrir el video. Prueba con otro archivo.') }} onLoadedMetadata={event => { const value = event.currentTarget.duration; if (Number.isFinite(value) && value > 0) setVideoDuration(Math.round(value * 1000)); else setError('No se pudo leer la duración del video.') }} onTimeUpdate={event => { if (event.currentTarget.currentTime >= 25) event.currentTarget.currentTime = 0 }} /> : <StreamingImage src={source} alt="Vista previa del momento" />}
          {overlay.text ? <span className="moment-text-overlay" style={{ left: `${overlay.x * 100}%`, top: `${overlay.y * 100}%`, color: overlay.color, fontFamily: overlay.font === 'mono' ? 'monospace' : overlay.font === 'serif' ? 'serif' : 'sans-serif' }}>{overlay.text}</span> : null}
        </div> : null}
        {file ? <p>{video ? `Se mostrarán los primeros ${Math.round(durationMs / 1000)} segundos.` : 'La foto se mostrará durante 6 segundos.'}</p> : null}
        <label>Descripción<textarea className="text-input" value={caption} maxLength={2000} onChange={event => setCaption(event.target.value)} /></label>
        <div className="row-actions"><button className="ghost-btn" type="button" onClick={() => setSearching(value => !value)}>Música de comunidad</button><label className="ghost-btn">{localBusy ? 'Abriendo audio…' : 'Audio del dispositivo'}<input type="file" hidden accept="audio/*" disabled={localBusy} onChange={event => { const audio = event.target.files?.[0]; event.target.value = ''; void chooseAudio(audio) }} /></label></div>
        {searching ? <MusicSearch onSelect={chooseTrack} /> : null}
        {music && range ? <section className="moment-selected-music" aria-label="Fragmento musical"><div className="row-between"><strong>{music.track.title} · {music.track.artist}</strong><button className="ghost-btn" type="button" onClick={() => setMusic(undefined)}>Quitar música</button></div>
          <label>Inicio del fragmento: {clock(range.startMs)} → {clock(range.endMs)}<input type="range" aria-label="Inicio del fragmento" min={0} max={range.maxStartMs} step={100} value={range.startMs} onChange={event => changeMusic({ startMs: Number(event.target.value) })} /></label>
          <div className="row-actions"><button className="ghost-btn" type="button" disabled={range.startMs <= 0} onClick={() => changeMusic({ startMs: Math.max(0, range.startMs - 1000) })}>−1 s</button><button className="ghost-btn" type="button" disabled={range.startMs >= range.maxStartMs} onClick={() => changeMusic({ startMs: Math.min(range.maxStartMs, range.startMs + 1000) })}>+1 s</button><small>Pista completa: {clock(music.track.durationMs)}</small></div>
          <ClipPreview key={music.track.id} selection={music} durationMs={durationMs} />
          <label>Presentación<select className="text-input" value={music.presentation} onChange={event => changeMusic({ presentation: event.target.value as MomentMusicSelection['presentation'] })}><option value="card">Tarjeta</option><option value="label">Etiqueta</option><option value="audio">Solo música</option></select></label>
          <label>Volumen de la música<input type="range" min={0} max={1} step={.05} value={music.volume} onChange={event => changeMusic({ volume: Number(event.target.value) })} /></label>
          {video ? <label>Volumen del video<input type="range" min={0} max={1} step={.05} value={music.originalVolume} onChange={event => changeMusic({ originalVolume: Number(event.target.value) })} /></label> : null}
          <details><summary>Opciones de audio</summary><label>Entrada suave (segundos)<input type="range" min={0} max={Math.min(5000, range.durationMs / 2) / 1000} step={.1} value={music.fadeInMs / 1000} onChange={event => changeMusic({ fadeInMs: Number(event.target.value) * 1000 })} /></label><label>Salida suave (segundos)<input type="range" min={0} max={Math.min(5000, range.durationMs / 2) / 1000} step={.1} value={music.fadeOutMs / 1000} onChange={event => changeMusic({ fadeOutMs: Number(event.target.value) * 1000 })} /></label>{video ? <label><input type="checkbox" checked={music.duckOriginal} onChange={event => changeMusic({ duckOriginal: event.target.checked })} />Bajar el audio del video mientras suena la música</label> : null}</details>
        </section> : null}
        <details><summary>Añadir texto a la imagen</summary><label>Texto superpuesto<input className="text-input" maxLength={120} value={overlay.text} onChange={event => setOverlay(current => ({ ...current, text: event.target.value }))} /></label><label>Color<input type="color" value={overlay.color} onChange={event => setOverlay(current => ({ ...current, color: event.target.value }))} /></label><label>Letra<select className="text-input" value={overlay.font} onChange={event => setOverlay(current => ({ ...current, font: event.target.value as MomentOverlay['font'] }))}><option value="sans">Normal</option><option value="serif">Serif</option><option value="mono">Monoespaciada</option></select></label><label>Posición horizontal<input type="range" min={.1} max={.9} step={.01} value={overlay.x} onChange={event => setOverlay(current => ({ ...current, x: Number(event.target.value) }))} /></label><label>Posición vertical<input type="range" min={.1} max={.9} step={.01} value={overlay.y} onChange={event => setOverlay(current => ({ ...current, y: Number(event.target.value) }))} /></label></details>
        <div className="moment-publication-settings"><label>Visible durante<select className="text-input" value={durationHours} onChange={event => setDurationHours(Number(event.target.value))}>{[6,12,24,48].map(hours => <option key={hours} value={hours}>{hours} horas</option>)}</select></label><label>Quién puede verlo<select className="text-input" value={visibility} onChange={event => setVisibility(event.target.value)}><option value="all">Todos</option><option value="followers">Mis seguidores</option><option value="following">Personas que sigo</option><option value="chats">Personas con las que chateo</option><option value="private">Solo yo</option></select></label></div>
        <label className="checkbox-inline"><input type="checkbox" checked={replies} onChange={event => setReplies(event.target.checked)} />Permitir respuestas</label>
        {error ? <p role="alert" className="error-text">{error}</p> : null}<button className="primary-btn" type="submit" disabled={!file || localBusy || (video && !videoDuration)}>{saving ? 'Publicando…' : 'Publicar momento'}</button>
      </fieldset></form>
    </article>
  </div>
}
