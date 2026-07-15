import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { SafeAvatar } from '../../components/SafeAvatar'
import { resolveSupabasePublicUrl, supabase } from '../../core/supabase'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { searchFallbackProfiles, submitFallbackUserReport } from '../../core/fallbackData'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'

type ProfileCard = {
  id: string
  name: string
  username: string
  bio: string
  avatarUrl: string
}

function mapProfile(row: GenericRow): ProfileCard {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
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
  const useFallback = shouldUseUbuntuBackend(isFallbackSession)

  useEffect(() => {
    async function loadProfiles() {
      if (useFallback) {
        setLoading(true)
        setError(null)
        try {
          const response = await searchFallbackProfiles('', 120)
          setItems(response.items.map(mapProfile))
        } catch (caught) {
          setError(friendlyError(caught, 'No se pudo cargar perfiles desde Ubuntu'))
        } finally {
          setLoading(false)
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
        setItems(((data ?? []) as GenericRow[]).map(mapProfile))
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo cargar perfiles'))
      } finally {
        setLoading(false)
      }
    }

    void loadProfiles()
  }, [useFallback])

  const filtered = useMemo(() => {
    const clean = query.trim().toLowerCase()
    if (!clean) return items
    return items.filter((profile) => {
      return (
        profile.name.toLowerCase().includes(clean) ||
        profile.username.toLowerCase().includes(clean) ||
        profile.bio.toLowerCase().includes(clean)
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
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar el reporte'))
    } finally {
      setReportingById((current) => ({ ...current, [profile.id]: false }))
    }
  }

  if (loading) return <StatusView title="Cargando búsqueda" description="Obteniendo perfiles..." />
  if (error) return <StatusView title="Búsqueda no disponible" description={error} />

  return (
    <section className="section-stack">
      <header className="section-header">
        <h2>Buscar usuarios</h2>
        <p>Versión web de búsqueda social.</p>
      </header>

      <input
        className="text-input"
        placeholder="Buscar por nombre, usuario o bio"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
      />

      {filtered.length === 0 ? (
        <StatusView title="Sin resultados" description="Prueba otro término de búsqueda." />
      ) : (
        <div className="list-stack">
          {filtered.map((profile) => (
            <article className="surface-card" key={profile.id}>
              <div className="profile-search-identity">
                <Link to={`/app/profile/${profile.id}`} aria-label={`Ver perfil de ${profile.name}`}>
                  <SafeAvatar url={profile.avatarUrl} name={profile.name || profile.id} size="md" />
                </Link>
                <div>
                  <Link className="inline-link" to={`/app/profile/${profile.id}`}>
                    <h3>{profile.name}</h3>
                  </Link>
                  <span>{profile.username ? `@${profile.username}` : '@usuario'}</span>
                </div>
              </div>
              <p>{profile.bio || 'Sin biografía'}</p>
              <div className="row-actions">
                <Link className="ghost-btn" to={`/app/profile/${profile.id}`}>
                  Ver perfil
                </Link>
              </div>
              {user?.id !== profile.id ? (
                <div className="composer">
                  <select
                    className="text-input tiny-input"
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
