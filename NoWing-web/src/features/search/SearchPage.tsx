import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { SafeAvatar } from '../../components/SafeAvatar'
import { resolveSupabasePublicUrl, supabase } from '../../core/supabase'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { searchFallbackProfiles, submitFallbackUserReport } from '../../core/fallbackData'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import { Flag, Search, X } from 'lucide-react'
import './searchPage.css'

type ProfileCard = {
  id: string
  name: string
  username: string
  bio: string
  avatarUrl: string
}

function mapProfile(row: GenericRow): ProfileCard {
  return {
    id: textOf(row, ['id', 'profileId'], ''),
    name: textOf(row, ['displayName', 'display_name', 'full_name'], 'Usuario'),
    username: textOf(row, ['username'], ''),
    bio: textOf(row, ['bio'], ''),
    avatarUrl: resolveSupabasePublicUrl('avatars', textOf(row, ['avatar_url', 'avatarUrl', 'photo_url', 'profile_image'], '')),
  }
}

export function SearchPage() {
  const { user, isFallbackSession } = useAuth()
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<ProfileCard[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reportReasonById, setReportReasonById] = useState<Record<string, string>>({})
  const [reportDetailsById, setReportDetailsById] = useState<Record<string, string>>({})
  const [reportingById, setReportingById] = useState<Record<string, boolean>>({})
  const [reportOpenId, setReportOpenId] = useState<string | null>(null)
  const [reportStatus, setReportStatus] = useState('')
  const useFallback = shouldUseUbuntuBackend(isFallbackSession)

  useEffect(() => {
    async function loadProfiles() {
      if (useFallback) {
        setLoading(true)
        setError(null)
        try {
          const response = await searchFallbackProfiles(query, 40)
          if (!cancelled) setItems(response.items.map(mapProfile).filter(profile => profile.id))
        } catch (caught) {
          if (!cancelled) setError(friendlyError(caught, 'No se pudo cargar perfiles desde Ubuntu'))
        } finally {
          if (!cancelled) setLoading(false)
        }
        return
      }

      if (!supabase) {
        setError('No hay servidor Ubuntu ni Supabase configurado para búsqueda.')
        setLoading(false)
        return
      }

      setLoading(true)
      setError(null)
      try {
        const { data, error: queryError } = await supabase.from('profiles').select('*').limit(120)
        if (queryError) throw queryError
        if (!cancelled) setItems(((data ?? []) as GenericRow[]).map(mapProfile).filter(profile => profile.id))
      } catch (caught) {
        if (!cancelled) setError(friendlyError(caught, 'No se pudo cargar perfiles'))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    let cancelled = false
    const timer = setTimeout(() => void loadProfiles(), 240)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [useFallback, query])

  const filtered = useMemo(() => {
    const normalize = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    const clean = normalize(query.trim())
    if (!clean) return items
    return items.filter((profile) => {
      return (
        normalize(profile.name).includes(clean) ||
        normalize(profile.username).includes(clean) ||
        normalize(profile.bio).includes(clean)
      )
    })
  }, [items, query])

  async function submitUserReport(profile: ProfileCard) {
    if (!user) return
    if (profile.id === user.id) {
      setError('No puedes reportarte a ti mismo.')
      return
    }
    const reason = (reportReasonById[profile.id] ?? '').trim()
    const details = (reportDetailsById[profile.id] ?? '').trim()
    if (!reason) {
      setError('Debes seleccionar un motivo para reportar.')
      return
    }
    setReportingById((current) => ({ ...current, [profile.id]: true }))
    setReportStatus('')
    setError(null)
    try {
      const reporterName = (user.email ?? 'Usuario').split('@')[0]
      if (useFallback) {
        await submitFallbackUserReport({
          reportedUserId: profile.id,
          reportedUserName: profile.name,
          reason,
          description: details || null,
        })
        setReportReasonById((current) => ({ ...current, [profile.id]: '' }))
        setReportDetailsById((current) => ({ ...current, [profile.id]: '' }))
        setReportOpenId(null)
        setReportStatus('Reporte enviado. Gracias por ayudarnos a cuidar la comunidad.')
        return
      }

      if (!supabase) throw new Error('No hay Supabase configurado para enviar el reporte.')
      const rpcResult = await supabase.rpc('secure_submit_report', {
        p_reported_user_id: profile.id,
        p_reported_user_name: profile.name,
        p_reason: reason,
        p_description: details || null,
        p_evidence_url: null,
        p_reporter_name: reporterName,
      })

      if (rpcResult.error) {
        const message = String(rpcResult.error.message ?? '').toLowerCase()
        const isMissingRpc =
          message.includes('could not find') ||
          message.includes('function') ||
          message.includes('does not exist') ||
          message.includes('42883')
        if (!isMissingRpc) throw rpcResult.error

        const withReporter = await supabase.from('reports').insert({
          reporter_id: user.id,
          reporter_name: reporterName,
          reported_user_id: profile.id,
          reported_user_name: profile.name,
          reason,
          description: details || null,
          evidence_url: null,
          status: 'pendiente',
        })
        if (withReporter.error) {
          const fallbackMessage = String(withReporter.error.message ?? '').toLowerCase()
          const reporterColumnMissing =
            fallbackMessage.includes('reporter_name') || fallbackMessage.includes('42703')
          if (!reporterColumnMissing) throw withReporter.error

          const withoutReporter = await supabase.from('reports').insert({
            reporter_id: user.id,
            reported_user_id: profile.id,
            reported_user_name: profile.name,
            reason,
            description: details || null,
            evidence_url: null,
            status: 'pendiente',
          })
          if (withoutReporter.error) throw withoutReporter.error
        }
      }

      setReportReasonById((current) => ({ ...current, [profile.id]: '' }))
      setReportDetailsById((current) => ({ ...current, [profile.id]: '' }))
      setReportOpenId(null)
      setReportStatus('Reporte enviado. Gracias por ayudarnos a cuidar la comunidad.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar el reporte'))
    } finally {
      setReportingById((current) => ({ ...current, [profile.id]: false }))
    }
  }

  return (
    <section className="section-stack user-search-page">
      <header className="section-header">
        <h2>Buscar usuarios</h2>
        <p>Encuentra personas por nombre o usuario.</p>
      </header>

      <label className="user-search-input"><Search size={19} aria-hidden="true"/><input
        className="text-input"
        aria-label="Buscar personas"
        placeholder="Buscar por nombre, usuario o bio"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
      /></label>

      {error ? <p role="alert">{error}</p> : null}
      {reportStatus ? <p role="status">{reportStatus}</p> : null}
      {loading ? <p role="status">Buscando personas…</p> : null}
      {!loading && !error && filtered.length === 0 ? (
        <StatusView title="Sin resultados" description="Prueba otro término de búsqueda." />
      ) : (
        <div className="user-search-results">
          {filtered.map((profile) => (
            <article className="user-search-card" key={profile.id}>
                <Link to={`/app/profile/${profile.id}`} aria-label={`Ver perfil de ${profile.name}`}>
                  <SafeAvatar url={profile.avatarUrl} name={profile.name || profile.id} size="md" />
                </Link>
                <div className="user-search-identity">
                  <Link className="inline-link" to={`/app/profile/${profile.id}`}>
                    <h3>{profile.name}</h3>
                  </Link>
                  <span>{profile.username ? `@${profile.username}` : '@usuario'}</span>
                  {profile.bio ? <p>{profile.bio}</p> : null}
                </div>
              <div className="user-search-actions">
                <Link className="ghost-btn" to={`/app/profile/${profile.id}`}>
                  Ver perfil
                </Link>
                {user?.id !== profile.id ? <button className="icon-btn" type="button" aria-label={`Reportar a ${profile.name}`} title="Reportar" aria-expanded={reportOpenId === profile.id} onClick={() => { setReportOpenId(current => current === profile.id ? null : profile.id); setError(null) }}>{reportOpenId === profile.id ? <X size={18}/> : <Flag size={17}/>}</button> : null}
              </div>
              {reportOpenId === profile.id && user?.id !== profile.id ? (
                <div className="user-search-report" role="region" aria-label={`Reportar a ${profile.name}`}>
                  <p>Cuéntanos por qué quieres reportar este perfil.</p>
                  <select
                    className="text-input tiny-input"
                    aria-label="Motivo del reporte"
                    value={reportReasonById[profile.id] ?? ''}
                    onChange={(event) =>
                      setReportReasonById((current) => ({
                        ...current,
                        [profile.id]: event.target.value,
                      }))
                    }
                  >
                    <option value="">Reportar: motivo</option>
                    <option value="spam">Spam</option>
                    <option value="acoso">Acoso</option>
                    <option value="suplantacion">Suplantación</option>
                    <option value="contenido_inapropiado">Contenido inapropiado</option>
                    <option value="otro">Otro</option>
                  </select>
                  <input
                    className="text-input"
                    aria-label="Detalles del reporte"
                    maxLength={500}
                    placeholder="Detalle (opcional)"
                    value={reportDetailsById[profile.id] ?? ''}
                    onChange={(event) =>
                      setReportDetailsById((current) => ({
                        ...current,
                        [profile.id]: event.target.value,
                      }))
                    }
                  />
                  <button
                    className="ghost-btn"
                    type="button"
                    disabled={reportingById[profile.id] === true}
                    onClick={() => void submitUserReport(profile)}
                  >
                    {reportingById[profile.id] === true ? 'Reportando...' : 'Reportar'}
                  </button>
                </div>
              ) : null}
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
