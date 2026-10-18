import { useEffect, useRef, useState } from 'react'
import './liveStreamMedia.css'


type Props = { stream: MediaStream | null; label: string; muted?: boolean; outputDeviceId?: string; className?: string }
type OutputElement = HTMLMediaElement & { setSinkId?: (deviceId: string) => Promise<void> }

function useStreamPlayback<T extends HTMLMediaElement>(stream: MediaStream | null, muted: boolean, outputDeviceId: string) {
  const element = useRef<T>(null)
  const attempt = useRef<() => Promise<void>>(async () => {})
  const preferences = useRef({ muted, outputDeviceId })
  useEffect(() => { preferences.current = { muted, outputDeviceId } }, [muted, outputDeviceId])
  const [issue, setIssue] = useState<{ stream: MediaStream; type: 'autoplay' | 'output' } | null>(null)
  useEffect(() => {
    const node = element.current as OutputElement | null
    if (!node || !stream) return
    let active = true
    node.srcObject = stream
    node.muted = preferences.current.muted
    const play = async () => {
      try {
        await node.play()
        if (active) setIssue(current => current?.type === 'autoplay' ? null : current)
      } catch (error) {
        if (active && !preferences.current.muted && error instanceof Error && error.name === 'NotAllowedError') setIssue({ stream, type: 'autoplay' })
      }
    }
    attempt.current = play
    const update = () => { void play() }
    node.addEventListener('loadedmetadata', update)
    stream.addEventListener('addtrack', update)
    return () => {
      active = false
      attempt.current = async () => {}
      node.removeEventListener('loadedmetadata', update)
      stream.removeEventListener('addtrack', update)
      node.pause()
      node.srcObject = null
    }
  }, [stream])
  useEffect(() => {
    const node = element.current as OutputElement | null
    if (!node || !stream) return
    let active = true
    node.muted = muted
    void (async () => {
      // Muted previews have no audible output to route. Setting an empty sink
      // can itself be denied by browser policy even when default playback works.
      if (node.setSinkId && !muted && (outputDeviceId || node.sinkId)) {
        try { await node.setSinkId(outputDeviceId); if (active) setIssue(current => current?.type === 'output' ? null : current) }
        catch {
          try { await node.setSinkId('') } catch { /* Keep browser's default route. */ }
          if (active && outputDeviceId) setIssue({ stream, type: 'output' })
        }
      } else if (active) setIssue(current => current?.type === 'output' ? null : current)
      if (active) await attempt.current()
    })()
    return () => { active = false }
  }, [stream, muted, outputDeviceId])
  const currentIssue = issue?.stream === stream ? issue : null
  return { element, play: () => void attempt.current(), blocked: !muted && currentIssue?.type === 'autoplay', outputFailed: currentIssue?.type === 'output' }
}

export function LiveStreamVideo({ stream, label, muted = false, outputDeviceId = '', className }: Props) {
  const { element, play, blocked, outputFailed } = useStreamPlayback<HTMLVideoElement>(stream, muted, outputDeviceId)
  return <>
    <video ref={element} className={className} aria-label={label} autoPlay muted={muted} playsInline />
    {blocked ? <button className="live-playback-action" type="button" onClick={play}>Activar audio de {label}</button> : null}
    {outputFailed ? <small className="live-output-error" role="status">No se pudo usar la salida de audio seleccionada. Elige otra en ajustes.</small> : null}
  </>
}

export function LiveStreamAudio({ stream, label, muted = false, outputDeviceId = '' }: Props) {
  const { element, play, blocked, outputFailed } = useStreamPlayback<HTMLAudioElement>(stream, muted, outputDeviceId)
  return <>
    <audio ref={element} autoPlay muted={muted} hidden />
    {blocked ? <button className="live-playback-action" type="button" onClick={play}>Escuchar a {label}</button> : null}
    {outputFailed ? <small className="live-output-error" role="status">No se pudo cambiar la salida de audio de {label}.</small> : null}
  </>
}
