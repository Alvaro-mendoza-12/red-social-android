import { useEffect, useState } from 'react'
import { reloadApplicationSafely } from '../core/chunkRecovery'
import { StatusView } from './StatusView'

/** A stalled import should offer a next step while the surrounding app stays usable. */
export function RouteLoadingStatus() {
  const [slow, setSlow] = useState(false)
  const [online, setOnline] = useState(() => navigator.onLine)
  const [blocked, setBlocked] = useState(false)
  useEffect(() => {
    const timer = setTimeout(() => setSlow(true), 12_000)
    const update = () => setOnline(navigator.onLine)
    window.addEventListener('online', update)
    window.addEventListener('offline', update)
    return () => {
      clearTimeout(timer)
      window.removeEventListener('online', update)
      window.removeEventListener('offline', update)
    }
  }, [])
  return <StatusView
    title={!online ? 'Sin conexión' : slow ? 'La carga está tardando' : 'Cargando sección…'}
    description={blocked ? 'Termina la llamada o transmisión y guarda tus borradores antes de recargar.'
      : !online ? 'Reconecta Internet para abrir esta sección.'
      : slow ? 'No hemos podido terminar la carga. Puedes abrir otra sección o reintentar cuando mejore la conexión.'
      : 'Espera un momento.'}
    action={slow || !online ? <button className="ghost-btn" type="button" onClick={() => setBlocked(!reloadApplicationSafely())}>Reintentar carga</button> : undefined}
  />
}
