import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { getOrCreateDirectChat } from '../../features/messages/directChat'
import { loadUbuntuChallengesBundle } from '../../core/challengesUbuntu'
import { isUbuntuPrimary } from '../../core/ubuntuBackend'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'

type RankingEntry = {
  userId: string
  ownerName: string
  totalVotes: number
  submissions: number
}

type CompletedPodium = {
  challengeId: string
  challengeTitle: string
  completedAt: string
  winners: Array<{ id: string; name: string }>
}

type PeriodFilter = 'all' | '30d' | '7d'

function cutoffByPeriod(period: PeriodFilter): number {
  const now = Date.now()
  if (period === '7d') return now - 7 * 24 * 60 * 60 * 1000
  if (period === '30d') return now - 30 * 24 * 60 * 60 * 1000
  return 0
}

export function ChallengesRankingPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [period, setPeriod] = useState<PeriodFilter>('all')
  const [ranking, setRanking] = useState<RankingEntry[]>([])
  const [podiums, setPodiums] = useState<CompletedPodium[]>([])
  const [searchQuery, setSearchQuery] = useState('')
  const [openingChatUserId, setOpeningChatUserId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const cutoffMs = useMemo(() => cutoffByPeriod(period), [period])
  const filteredRanking = useMemo(() => {
    const query = searchQuery.trim().toLowerCase()
    if (!query) return ranking
    return ranking.filter((entry) => {
      return (
        entry.ownerName.toLowerCase().includes(query) ||
        entry.userId.toLowerCase().includes(query)
      )
    })
  }, [ranking, searchQuery])

  const loadRanking = useCallback(async () => {
    if (!user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)

    try {
      if (isUbuntuPrimary) {
        const bundle = await loadUbuntuChallengesBundle(user.id)
        setRanking(bundle.ranking)
        const active = bundle.activeChallenge
        setPodiums(active?.status === 'completed' ? [{
          challengeId: active.challengeId,
          challengeTitle: bundle.challengeMeta?.title || 'Reto',
          completedAt: active.createdAt,
          winners: bundle.winnerProfiles,
        }] : [])
        return
      }
      if (!supabase) return
      const { data: submissionsRows, error: submissionsError } = await supabase
        .from('challenge_submissions')
        .select('id,user_id,vote_count,created_at,active_challenge_id')
        .order('created_at', { ascending: false })
        .limit(3000)
      if (submissionsError) throw submissionsError
      const rawSubmissions = (submissionsRows ?? []) as GenericRow[]

      const filteredSubmissions = rawSubmissions.filter((submission) => {
        if (cutoffMs <= 0) return true
        const created = Date.parse(textOf(submission, ['created_at'], ''))
        if (Number.isNaN(created)) return true
        return created >= cutoffMs
      })

      const userIds = Array.from(
        new Set(filteredSubmissions.map((submission) => textOf(submission, ['user_id'], '')).filter(Boolean)),
      )

      const nameById = new Map<string, string>()
      if (userIds.length > 0) {
        const { data: profileRows } = await supabase.from('profiles').select('*').in('id', userIds)
        ;((profileRows ?? []) as GenericRow[]).forEach((profile) => {
          const id = textOf(profile, ['id'], '')
          if (!id) return
          nameById.set(
            id,
            textOf(profile, ['display_name', 'displayName', 'full_name', 'username'], 'Usuario'),
          )
        })
      }

      const rankingMap = new Map<string, RankingEntry>()
      filteredSubmissions.forEach((submission) => {
        const userId = textOf(submission, ['user_id'], '')
        if (!userId) return
        const current = rankingMap.get(userId) ?? {
          userId,
          ownerName: nameById.get(userId) ?? userId,
          totalVotes: 0,
          submissions: 0,
        }
        current.totalVotes += Number(submission.vote_count ?? 0)
        current.submissions += 1
        rankingMap.set(userId, current)
      })

      setRanking(
        Array.from(rankingMap.values()).sort((a, b) => {
          if (a.totalVotes !== b.totalVotes) return b.totalVotes - a.totalVotes
          return b.submissions - a.submissions
        }),
      )

      const { data: completedRows, error: completedError } = await supabase
        .from('active_challenges')
        .select('challenge_id,winner_ids,created_at,status')
        .eq('status', 'completed')
        .order('created_at', { ascending: false })
        .limit(30)
      if (completedError) throw completedError

      const completed = (completedRows ?? []) as GenericRow[]
      const challengeIds = Array.from(
        new Set(completed.map((row) => textOf(row, ['challenge_id'], '')).filter(Boolean)),
      )
      const challengeTitleById = new Map<string, string>()
      if (challengeIds.length > 0) {
        const { data: challengeRows } = await supabase
          .from('challenges_master')
          .select('id,title')
          .in('id', challengeIds)
        ;((challengeRows ?? []) as GenericRow[]).forEach((challenge) => {
          const id = textOf(challenge, ['id'], '')
          if (!id) return
          challengeTitleById.set(id, textOf(challenge, ['title'], 'Reto'))
        })
      }

      const winnerIds = Array.from(
        new Set(
          completed.flatMap((row) => {
            if (!Array.isArray(row.winner_ids)) return []
            return row.winner_ids.map((entry) => String(entry)).filter(Boolean)
          }),
        ),
      )
      const winnerNameById = new Map<string, string>()
      if (winnerIds.length > 0) {
        const { data: winnerRows } = await supabase.from('profiles').select('*').in('id', winnerIds)
        ;((winnerRows ?? []) as GenericRow[]).forEach((winner) => {
          const id = textOf(winner, ['id'], '')
          if (!id) return
          winnerNameById.set(
            id,
            textOf(winner, ['display_name', 'displayName', 'full_name', 'username'], 'Usuario'),
          )
        })
      }

      setPodiums(
        completed.map((row) => {
          const challengeId = textOf(row, ['challenge_id'], '')
          const winnerList = Array.isArray(row.winner_ids)
            ? row.winner_ids.map((entry) => {
                const id = String(entry)
                return {
                  id,
                  name: winnerNameById.get(id) ?? id,
                }
              })
            : []
          return {
            challengeId,
            challengeTitle: challengeTitleById.get(challengeId) ?? 'Reto',
            completedAt: textOf(row, ['created_at'], ''),
            winners: winnerList,
          } satisfies CompletedPodium
        }),
      )
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar ranking global'))
    } finally {
      setLoading(false)
    }
  }, [cutoffMs, user])

  useEffect(() => {
    void loadRanking()
  }, [loadRanking])

  useEffect(() => {
    if (isUbuntuPrimary) {
      return subscribeUbuntuRealtime({
        channel: 'challenges:live',
        onEvent: () => void loadRanking(),
      })
    }
    const client = supabase
    if (!client || !user) return
    const channel = client
      .channel('challenges-ranking-live')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'challenge_submissions' }, () => {
        void loadRanking()
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'active_challenges' }, () => {
        void loadRanking()
      })
      .subscribe()
    return () => {
      void client.removeChannel(channel)
    }
  }, [loadRanking, user])

  if (!supabase && !isUbuntuPrimary) {
    return <StatusView title="Backend no configurado" description="Configura VITE_FALLBACK_API_URL para ranking en Ubuntu." />
  }

  async function openDirectChat(userId: string) {
    if (!user || !userId || userId === user.id) return
    setOpeningChatUserId(userId)
    setError(null)
    try {
      const chatId = await getOrCreateDirectChat(user.id, userId)
      navigate(`/app/messages/${chatId}`)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo abrir chat directo'))
    } finally {
      setOpeningChatUserId(null)
    }
  }

  return (
    <section className="section-stack">
      <div className="row-between">
        <Link to="/app/challenges" className="inline-link">
          ← Volver a retos
        </Link>
        <h2>Ranking Global</h2>
      </div>

      <article className="surface-card section-stack">
        <h3>Filtro de periodo</h3>
        <div className="chip-row">
          {[
            { key: 'all', label: 'Todo' },
            { key: '30d', label: '30 días' },
            { key: '7d', label: '7 días' },
          ].map((option) => (
            <button
              key={option.key}
              className={period === option.key ? 'chip-btn active' : 'chip-btn'}
              type="button"
              onClick={() => setPeriod(option.key as PeriodFilter)}
            >
              {option.label}
            </button>
          ))}
        </div>
        <div className="composer">
          <input
            className="text-input"
            placeholder="Buscar por nombre o ID..."
            value={searchQuery}
            onChange={(event) => setSearchQuery(event.target.value)}
          />
          {searchQuery.trim() ? (
            <button className="ghost-btn" type="button" onClick={() => setSearchQuery('')}>
              Limpiar
            </button>
          ) : null}
        </div>
      </article>

      {loading ? (
        <StatusView title="Cargando ranking" description="Espera un momento..." />
      ) : error ? (
        <StatusView title="Ranking no disponible" description={error} />
      ) : (
        <>
          <article className="surface-card section-stack">
            <h3>Top usuarios ({filteredRanking.length})</h3>
            {ranking.length === 0 ? (
              <p>No hay datos de ranking todavía.</p>
            ) : filteredRanking.length === 0 ? (
              <p>No hay coincidencias para tu búsqueda.</p>
            ) : (
              <div className="list-stack">
                {filteredRanking.slice(0, 50).map((entry, index) => {
                  const openingChat = openingChatUserId === entry.userId
                  const isMe = entry.userId === user?.id
                  return (
                    <div key={entry.userId} className="surface-subcard row-between">
                      <div>
                        <strong>
                          #{index + 1} {entry.ownerName}
                        </strong>
                        <small>{entry.submissions} participaciones</small>
                      </div>
                      <div className="row-actions">
                        <span className="folder-tag">{entry.totalVotes} votos</span>
                        {!isMe ? (
                          <>
                            <Link className="mini-action-btn" to={`/app/profile/${entry.userId}`}>
                              Perfil
                            </Link>
                            <button
                              className="mini-action-btn"
                              type="button"
                              disabled={openingChat}
                              onClick={() => void openDirectChat(entry.userId)}
                            >
                              {openingChat ? 'Abriendo...' : 'Mensaje'}
                            </button>
                          </>
                        ) : null}
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </article>

          <article className="surface-card section-stack">
            <h3>Podios recientes</h3>
            {podiums.length === 0 ? (
              <p>No hay podios completados todavía.</p>
            ) : (
              <div className="list-stack">
                {podiums.map((podium, index) => (
                  <div key={`${podium.challengeId}-${index}`} className="surface-subcard section-stack">
                    <div className="row-between">
                      <strong>{podium.challengeTitle}</strong>
                      <small>{formatDateTime(podium.completedAt)}</small>
                    </div>
                    {podium.winners.length > 0 ? (
                      <div className="row-actions">
                        {podium.winners.map((winner) => {
                          const openingChat = openingChatUserId === winner.id
                          const isMe = winner.id === user?.id
                          return (
                            <span key={`${podium.challengeId}-${winner.id}`} className="row-actions">
                              <Link className="inline-link" to={`/app/profile/${winner.id}`}>
                                {winner.name}
                              </Link>
                              {!isMe ? (
                                <button
                                  className="mini-action-btn"
                                  type="button"
                                  disabled={openingChat}
                                  onClick={() => void openDirectChat(winner.id)}
                                >
                                  {openingChat ? 'Abriendo...' : 'Mensaje'}
                                </button>
                              ) : null}
                            </span>
                          )
                        })}
                      </div>
                    ) : (
                      <p>Sin ganadores registrados</p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </article>
        </>
      )}
    </section>
  )
}
