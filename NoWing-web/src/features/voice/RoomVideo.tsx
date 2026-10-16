import { useRef } from 'react'
import { Maximize } from 'lucide-react'
import { LiveStreamVideo } from '../../components/LiveStreamMedia'
import { useLiveVideoAvailable } from '../../core/media/useLiveVideoAvailable'
export function RoomVideo({ stream, label }: { stream: MediaStream; label: string }) {
  const tile = useRef<HTMLElement>(null), active = useLiveVideoAvailable(stream)
  return <figure ref={tile} className="voice-video-tile" hidden={!active}>
    <LiveStreamVideo stream={stream} label={label} muted />
    <span>{label}</span>
    {typeof document.documentElement.requestFullscreen === 'function' ? <button type="button" className="voice-video-expand" aria-label={`Ampliar ${label}`} onClick={() => void (document.fullscreenElement ? document.exitFullscreen() : tile.current?.requestFullscreen())?.catch(() => {})}><Maximize size={18} /></button> : null}
  </figure>
}
