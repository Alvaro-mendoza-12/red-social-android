import { StreamingImage } from './StreamingMedia'
import { runtimeStoragePreviewUrl } from '../core/runtimeBackend'
import './postPhotoGallery.css'

/** A compact feed preview; the viewer retains every original and its index. */
export function PostPhotoGallery({ urls, author, priority = false, onOpen }: {
  urls: string[]; author: string; priority?: boolean; onOpen: (index: number) => void
}) {
  const visible = urls.slice(0, 4)
  const remaining = urls.length - visible.length
  return <div className="post-photo-gallery">
    <div className={`post-photo-grid${urls.length === 1 ? ' post-photo-single' : urls.length === 2 ? ' post-photo-pair' : ''}`}>
      {visible.map((url, index) => <button key={`${index}/${url}`} type="button" className="post-photo-cell"
        aria-label={`Ampliar imagen ${index + 1} de ${author}`} onClick={() => onOpen(index)}>
        <StreamingImage src={runtimeStoragePreviewUrl(url, urls.length > 1 ? 480 : 960)}
          alt={`Foto ${index + 1} de ${urls.length} de ${author}`} className="post-photo-image"
          loading={priority ? 'eager' : 'lazy'} decoding="async" fetchPriority={priority && index === 0 ? 'high' : 'auto'} />
      </button>)}
    </div>
    {remaining > 0 ? <button type="button" className="post-photo-more" onClick={() => onOpen(visible.length)}>
      Ver {remaining} {remaining === 1 ? 'foto más' : 'fotos más'}
    </button> : null}
  </div>
}
