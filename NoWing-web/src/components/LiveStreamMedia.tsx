import { useEffect, useRef, useState } from 'react'
import './liveStreamMedia.css'


type Props = { stream: MediaStream | null; label: string; muted?: boolean; outputDeviceId?: string; className?: string }
type OutputElement = HTMLMediaElement & { setSinkId?: (deviceId: string) => Promise<void> }

function useStreamPlayback<T extends HTMLMediaElement>(stream: MediaStream | null, muted: boolean, outputDeviceId: string) {
  const element = useRef<T>(null)
  const attempt = useRef<() => Promise<void>>(async () => {})
  const [issue, setIssue] = useState<{ stream: MediaStream; type: 'autoplay' | 'output' } | null>(null)
  useEffect(() => {
    const node = element.current as OutputElement | null
    if (!node || !stream) return
    let active = true
    node.srcObject = stream
    node.muted = muted
    const play = async () => {
      try {
        await node.play()
        if (active) setIssue(current => current?.type === 'autoplay' ? null : current)
      } catch (error) {
        if (active && !muted && error instanceof Error && error.name === 'NotAllowedError') setIssue({ stream, type: 'autoplay' })
      }
    }
    attempt.current = play
    const update = () => { void play() }
    node.addEventListener('loadedmetadata', update)
    stream.addEventListener('addtrack', update)
    void (async () => {
      if (node.setSinkId) {
        try { await node.setSinkId(outputDeviceId); if (active) setIssue(current => current?.type === 'output' ? null : current) }
        catch { if (active) setIssue({ stream, type: 'output' }) }
      }
      if (active) await play()
    })()
    return () => {
      active = false
      attempt.current = async () => {}
      node.removeEventListener('loadedmetadata', update)
      stream.removeEventListener('addtrack', update)
      node.pause()
      node.srcObject = null
    }
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
