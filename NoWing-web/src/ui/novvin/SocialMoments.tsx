import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { useAuth } from '../../core/auth/AuthProvider'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'
import { fetchMomentsFeed, type MomentItem } from '../../features/moments/momentsData'
import { MomentComposer } from '../../features/moments/MomentComposer'

export function SocialMoments() {
  const { user } = useAuth()
  return <SocialMomentsContent key={user?.id || 'guest'} />
}
function SocialMomentsContent() {
  const { user } = useAuth()
  const ownerId = user?.id
  const [items, setItems] = useState<MomentItem[]>([])
  const [creating, setCreating] = useState(false)
  const request = useRef(0)
  const load = useCallback(async () => {
    const sequence = ++request.current
    if (!ownerId) return
    try {
      const rows = await fetchMomentsFeed(ownerId)
      if (sequence === request.current) setItems(rows)
    } catch {
      /* The main feed remains available when moments cannot refresh. */
    }
  }, [ownerId])
  useEffect(() => {
    const timer = window.setTimeout(() => { void load() }, 0)
    return () => {
      window.clearTimeout(timer)
      request.current++
    }
  }, [load])
  useEffect(
    () =>
      subscribeUbuntuRealtime({
        channel: 'moments:live',
        onEvent: () => {
          void load()
        },
      }),
    [load],
  )
  const grouped = Array.from(new Map(items.map((item) => [item.userId, item])).values())
  return (
    <>
      <div className="social-moment-rail" aria-label="Momentos">
        <button
          type="button"
          className="social-moment-tile social-moment-create"
          onClick={() => setCreating(true)}
        >
          <Plus />
          <strong>Crear momento</strong>
        </button>
        {grouped.map((item) => (
          <Link
            key={item.userId}
            to={`/app/moments/view/${item.id}`}
            className="social-moment-tile"
            aria-label={`Ver momento de ${item.ownerName}`}
          >
            {item.mediaType === 'video' ? (
              <StreamingVideo src={item.mediaUrl} muted playsInline preload="metadata" />
            ) : (
              <StreamingImage src={item.mediaUrl} alt="" loading="lazy" />
            )}
            <strong>{item.userId === user?.id ? 'Tu momento' : item.ownerName}</strong>
          </Link>
        ))}
      </div>
      {creating && user ? (
        <MomentComposer
          key={user.id}
          onClose={() => setCreating(false)}
          onPublished={() => {
            setCreating(false)
            void load()
          }}
        />
      ) : null}
    </>
  )
}
