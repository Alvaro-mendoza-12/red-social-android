import { useEffect, useRef, useState, type FormEvent } from 'react'
import { activeAnnouncement, announcementSchedule, loadSystemAnnouncements, publishSystemAnnouncement, revokeSystemAnnouncement, type SystemAnnouncement } from '../../core/systemAnnouncements'
import '../../components/systemAnnouncements.css'

export function SystemAnnouncementAdmin({ canManage }: { canManage: boolean }) {
  const [notices, setNotices] = useState<SystemAnnouncement[]>([]), [busy, setBusy] = useState(false)
  const [kind, setKind] = useState<SystemAnnouncement['kind']>('info')
  const [title, setTitle] = useState(''), [body, setBody] = useState('')
  const [start, setStart] = useState(''), [end, setEnd] = useState(''), [version, setVersion] = useState('')
  const [result, setResult] = useState(''), [error, setError] = useState('')
  const [prepared, setPrepared] = useState<SystemAnnouncement | null>(null), [preview, setPreview] = useState(false)
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const abort = new AbortController()
    void loadSystemAnnouncements(true, abort.signal).then(setNotices).catch(() => { if (!abort.signal.aborted) setError('No se pudieron cargar los avisos. Vuelve a intentar.') })
    return () => abort.abort()
  }, [])
  useEffect(() => { if (preview) dialog.current?.showModal() }, [preview])
  function changed() { setPrepared(null); setError(''); setResult('') }
  function review(event: FormEvent) {
    event.preventDefault()
    if (!canManage || busy) return
    const now = Date.now(), startMs = Date.parse(start), endMs = Date.parse(end)
    const code = Number(version)
    if (kind === 'maintenance' && (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs || endMs <= now || endMs + 86400000 >= now + 90 * 86400000)) {
      setError('Elige un horario válido dentro de los próximos 89 días. El fin debe ser posterior al inicio.'); return
    }
    if (kind === 'update' && (!Number.isInteger(code) || code < 1 || code > 2100000000)) { setError('Indica el código de versión publicado en Google Play.'); return }
    if (!prepared) setPrepared({ id: crypto.randomUUID(), kind, title: title.trim(), body: body.trim(),
      startsAt: kind === 'maintenance' ? new Date(startMs).toISOString() : null,
      endsAt: kind === 'maintenance' ? new Date(endMs).toISOString() : null,
      expiresAt: new Date(kind === 'maintenance' ? endMs + 86400000 : now + 7 * 86400000).toISOString(),
      versionCode: kind === 'update' ? code : null })
    setError(''); setPreview(true)
  }
  async function publish() {
    if (!prepared || busy || !canManage) return
    setBusy(true); setError('')
    try {
      await publishSystemAnnouncement(prepared)
      setPreview(false); setPrepared(null); setTitle(''); setBody('')
      setResult('Aviso publicado. El servidor procesa las notificaciones para todas las cuentas.')
      setNotices(await loadSystemAnnouncements(true))
    } catch { setError('No se pudo completar la publicación. Reintentar conserva el mismo aviso para evitar duplicados.') }
    finally { setBusy(false) }
  }
  async function withdraw(notice: SystemAnnouncement) {
    if (!canManage || busy) return
    setBusy(true); setError('')
    try {
      await revokeSystemAnnouncement(notice.id)
      setNotices(items => items.map(item => item.id === notice.id ? { ...item, revokedAt: new Date().toISOString() } : item))
      setResult('Aviso retirado. Una notificación ya recibida puede seguir visible en el teléfono.')
    } catch { setError('No se pudo retirar el aviso. Vuelve a intentar.') }
    finally { setBusy(false) }
  }
  return <section className="surface-subcard section-stack" aria-labelledby="system-notice-admin-title">
    <h2 id="system-notice-admin-title">Avisos de Novvin</h2>
    <p>Novedades, mantenimiento y actualizaciones. Revisa el mensaje antes de notificar a todas las cuentas.</p>
    {canManage && <form className="section-stack" onSubmit={review}>
      <label>Tipo de aviso<select value={kind} disabled={busy} onChange={event => { setKind(event.target.value as SystemAnnouncement['kind']); changed() }}>
        <option value="info">Novedad</option><option value="maintenance">Mantenimiento</option><option value="update">Actualización</option>
      </select></label>
      <label>Título<input value={title} onChange={event => { setTitle(event.target.value); changed() }} minLength={3} maxLength={100} required disabled={busy} /></label>
      <label>Mensaje para los usuarios<textarea value={body} onChange={event => { setBody(event.target.value); changed() }} minLength={5} maxLength={1200} rows={4} required disabled={busy} /></label>
      {kind === 'maintenance' && <><p>Horario de {Intl.DateTimeFormat().resolvedOptions().timeZone}</p>
        <label>Inicio del mantenimiento<input type="datetime-local" value={start} onChange={event => { setStart(event.target.value); changed() }} required disabled={busy} /></label>
        <label>Fin del mantenimiento<input type="datetime-local" value={end} onChange={event => { setEnd(event.target.value); changed() }} required disabled={busy} /></label></>}
      {kind === 'update' && <label>Código de versión de Google Play<input type="number" min={1} max={2100000000} step={1} value={version} onChange={event => { setVersion(event.target.value); changed() }} required disabled={busy} />
        <small>Publícalo cuando la nueva versión ya esté disponible.</small></label>}
      <button className="primary-btn" type="submit" disabled={busy}>Revisar antes de publicar</button>
    </form>}
    {error && <p role="alert" className="error-text">{error}</p>}{result && <p role="status">{result}</p>}
    {notices.length === 0 && <p>No hay avisos publicados.</p>}
    {notices.map(notice => <article key={notice.id} className="surface-subcard section-stack"><strong>{notice.title}</strong><p>{notice.body}</p>
      {announcementSchedule(notice) && <p>{announcementSchedule(notice)}</p>}
      <small>{activeAnnouncement(notice) ? 'Publicado' : 'Retirado o finalizado'}</small>
      {canManage && activeAnnouncement(notice) && <button type="button" className="ghost-btn" disabled={busy} onClick={() => void withdraw(notice)}>Retirar aviso</button>}
    </article>)}
    {preview && prepared && <dialog ref={dialog} className="system-notice-confirm" aria-labelledby="system-notice-confirm-title" onCancel={event => { if (busy) event.preventDefault(); else setPreview(false) }}>
      <h2 id="system-notice-confirm-title">Publicar para todas las cuentas</h2><strong>{prepared.title}</strong><p>{prepared.body}</p>
      {announcementSchedule(prepared) && <p>{announcementSchedule(prepared)}</p>}
      <p>Las cuentas recibirán una notificación de Novvin.</p>
      {error && <p role="alert" className="error-text">{error}</p>}
      <div className="system-notice-actions"><button type="button" className="ghost-btn" disabled={busy} onClick={() => setPreview(false)}>Volver a editar</button>
        <button type="button" className="primary-btn" disabled={busy} onClick={() => void publish()}>{busy ? 'Publicando…' : 'Publicar aviso'}</button></div>
    </dialog>}
  </section>
}
