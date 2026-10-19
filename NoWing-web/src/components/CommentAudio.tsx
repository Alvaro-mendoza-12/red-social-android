import { useCallback, useState } from 'react'
import { StreamingAudio } from './StreamingMedia'

export function CommentAudio({ url, name }: { url: string; name: string }) {
  const [failed, setFailed] = useState(false), [attempt, setAttempt] = useState(0)
  const unavailable = useCallback(() => setFailed(true), [])
  return <div className="comment-audio">
    {failed ? <div role="status"><p>Este audio no pudo cargarse. Reintenta o pide al autor que lo vuelva a enviar.</p><button type="button" className="ghost-btn" onClick={() => { setFailed(false); setAttempt(value => value + 1) }}>Reintentar audio</button></div>
      : <StreamingAudio key={attempt} src={url} controls preload="metadata" aria-label={`Audio de ${name}`} onError={unavailable} onSourceUnavailable={unavailable}/>}
  </div>
}
