import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../core/auth/AuthProvider'
import { loadFallbackProfileMe } from '../../core/fallbackData'
import { formatDateTime, textOf, type GenericRow } from '../../core/utils'
import { getStorageReadIdentity, subscribeStorageReadIdentity, type StorageReadIdentity } from '../../core/media/storageReadSession'
import './reports.css'

function readableStatus(value: string): string {
  const normalized = value.trim().toLowerCase()
  const statuses: Record<string, string> = {
    pending: 'Pendiente', pendiente: 'Pendiente', in_review: 'En revisión', en_proceso: 'En revisión',
    resolved: 'Resuelto', finalizado: 'Resuelto', rejected: 'Rechazado', rechazado: 'Rechazado',
  }
  return statuses[normalized] || 'Recibido'
}

function reportTitle(row: GenericRow): string {
  const type = textOf(row, ['target_type', 'reported_type', 'type'], '').toLowerCase()
  if (type.includes('video')) return 'Reporte de video'
  if (type.includes('post')) return 'Reporte de publicación'
  if (type.includes('user') || type.includes('profile')) return 'Reporte de usuario'
  if (type.includes('message')) return 'Reporte de mensaje'
  return 'Reporte enviado'
}

export function MyReportsPage() {
  const { user, isFallbackSession } = useAuth()
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const owner = isFallbackSession && identity.provider === 'fallback' && identity.accountId === user?.id
    ? identity : null
  // A new session must never render the previous session's private reports,
  // including logout/login to the same account and identity changes before auth updates.
  return <SessionReports key={owner ? owner.generation : 'signed-out'} identity={owner} />
}

function SessionReports({ identity }: { identity: StorageReadIdentity | null }) {
  const isFallbackSession = Boolean(identity)
  const requestGeneration = useRef(0)
  const [reports, setReports] = useState<GenericRow[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const refresh = useCallback(async () => {
    if (!identity || identity !== getStorageReadIdentity()) { setLoading(false); return }
    const generation = ++requestGeneration.current
    const isCurrent = () => generation === requestGeneration.current && identity === getStorageReadIdentity()
    setLoading(true); setError('')
    try {
      const data = await loadFallbackProfileMe(identity)
      if (!isCurrent()) return
      setReports(data.reports || [])
      setTotal(data.stats.reportsSent || 0)
    } catch {
      if (!isCurrent()) return
      setError('No se pudieron cargar tus reportes. Revisa la conexión y vuelve a intentar.')
    } finally { if (isCurrent()) setLoading(false) }
  }, [identity])
  useEffect(() => {
    void refresh()
    return () => { requestGeneration.current += 1 }
  }, [refresh])

  return <section className="reports-page">
    <div className="surface-card reports-header"><div><h2>Reportes enviados</h2><p>Solo tú puedes consultar tus reportes. El equipo de moderación revisa cada caso.</p></div><button type="button" className="ghost-btn" disabled={loading} onClick={() => void refresh()}>Actualizar</button></div>
    {!isFallbackSession && <div className="surface-card reports-state">Inicia sesión en Novvin para consultar tus reportes.</div>}
    {loading && <div className="surface-card reports-state" role="status">Cargando reportes...</div>}
    {error && <div className="surface-card reports-state" role="alert"><p>{error}</p><button className="ghost-btn" type="button" onClick={() => void refresh()}>Reintentar</button></div>}
    {!loading && !error && isFallbackSession && <>
      {reports.length === 0 ? <div className="surface-card reports-state"><h3>Sin reportes</h3><p>Cuando envíes uno, podrás consultar su estado aquí.</p><Link to="/app/social" className="ghost-btn">Volver a Social</Link></div> : <><p className="reports-count">{total > reports.length ? `Mostrando los ${reports.length} reportes más recientes de ${total}.` : `${reports.length} ${reports.length === 1 ? 'reporte' : 'reportes'}.`}</p><div className="reports-list">{reports.map((row, index) => {
        const id = textOf(row, ['id', 'report_id'], '') || `report-${index}`
        const status = readableStatus(textOf(row, ['status'], 'pending'))
        const reason = textOf(row, ['reason', 'description', 'details'], '')
        const createdAt = textOf(row, ['created_at', 'createdAt'], '')
        return <article className="surface-card reports-item" key={id}><div className="reports-item-head"><h3>{reportTitle(row)}</h3><span className="reports-status">{status}</span></div>{reason && <p>{reason}</p>}{createdAt && <small>Enviado el {formatDateTime(createdAt)}</small>}</article>
      })}</div></>}
    </>}
  </section>
}
