import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../core/auth/AuthProvider'
import { loadFallbackProfileMe } from '../../core/fallbackData'
import { formatDateTime, textOf, type GenericRow } from '../../core/utils'
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
  const { isFallbackSession } = useAuth()
  const [reports, setReports] = useState<GenericRow[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const refresh = useCallback(async () => {
    if (!isFallbackSession) { setLoading(false); return }
    setLoading(true); setError('')
    try {
      const data = await loadFallbackProfileMe()
      setReports(data.reports || [])
      setTotal(data.stats.reportsSent || 0)
    } catch {
      setError('No se pudieron cargar tus reportes. Revisa la conexión y vuelve a intentar.')
    } finally { setLoading(false) }
  }, [isFallbackSession])
  useEffect(() => { void refresh() }, [refresh])

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
