import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { getOrCreateDirectChat } from '../../features/messages/directChat'
import { callUbuntuRpc } from '../../core/fallbackRpc'
import { uploadFallbackFile } from '../../core/fallbackData'
import { insertUbuntuChallengeSubmission, loadUbuntuChallengesBundle } from '../../core/challengesUbuntu'
import { isUbuntuPrimary } from '../../core/ubuntuBackend'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'

type ActiveChallenge = {
  id: string
  challengeId: string
  status: string
  expiresAt: string
  votingExpiresAt: string
  winnerIds: string[]
  createdAt: string
}

type ChallengeMaster = {
  id: string
  title: string
  description: string
  challengeType: string
  difficulty: string
}

type ChallengeSubmission = {
  id: string
  activeChallengeId: string
  userId: string
  contentUrl: string
  textContent: string
  voteCount: number
  createdAt: string
  votedByMe: boolean
  ownerName: string
}

type ChallengeRankingRow = {
  userId: string
  ownerName: string
  totalVotes: number
  submissions: number
}

type WinnerProfile = {
  id: string
  name: string
}

function inferSubmissionMediaType(file: File): 'image' | 'video' | 'audio' {
  if (file.type.startsWith('video/')) return 'video'
  if (file.type.startsWith('audio/')) return 'audio'
  return 'image'
}

function toStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.map((entry) => String(entry).trim()).filter((entry) => entry.length > 0)
}

function toMillis(value: string): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function remainingLabel(targetAt: string): string {
  const targetMs = toMillis(targetAt)
  if (targetMs <= 0) return 'Sin fecha'
  const diff = targetMs - Date.now()
  if (diff <= 0) return 'Finalizado'
  const hours = Math.floor(diff / (60 * 60 * 1000))
  const minutes = Math.floor((diff % (60 * 60 * 1000)) / (60 * 1000))
  if (hours > 0) return `${hours}h ${minutes}m`
  return `${Math.max(1, minutes)}m`
}

async function tryVoteRpc(submissionId: string): Promise<void> {
  if (isUbuntuPrimary) {
    await callUbuntuRpc('toggle_challenge_vote', { p_submission_id: submissionId })
    return
  }
  if (!supabase) return
  const client = supabase
  const errors: string[] = []

  const tryCalls: Array<() => Promise<{ ok: boolean; error?: unknown }>> = [
    async () => {
      const { error } = await client.rpc('toggle_challenge_vote', { p_submission_id: submissionId })
      return { ok: !error, error }
    },
    async () => {
      const { error } = await client.rpc('v2026_challenge_vote', { p_id: submissionId })
      return { ok: !error, error }
    },
    async () => {
      const { error } = await client.rpc('challenge_v3_vote_toggle', { target_id_text: submissionId })
      return { ok: !error, error }
    },
  ]

  for (const call of tryCalls) {
    const result = await call()
    if (result.ok) return
    errors.push(String(result.error ?? 'error'))
  }

  throw new Error(errors.join(' | '))
}

export function ChallengesPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [activeChallenge, setActiveChallenge] = useState<ActiveChallenge | null>(null)
  const [challengeMeta, setChallengeMeta] = useState<ChallengeMaster | null>(null)
  const [submissions, setSubmissions] = useState<ChallengeSubmission[]>([])
  const [ranking, setRanking] = useState<ChallengeRankingRow[]>([])
  const [winnerProfiles, setWinnerProfiles] = useState<WinnerProfile[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [textAnswer, setTextAnswer] = useState('')
  const [uploadingFile, setUploadingFile] = useState(false)
  const [submissionFilter, setSubmissionFilter] = useState<'all' | 'mine' | 'unvoted'>('all')
  const [submissionSort, setSubmissionSort] = useState<'top' | 'new'>('top')
  const [submissionSearch, setSubmissionSearch] = useState('')
  const [openingChatUserId, setOpeningChatUserId] = useState<string | null>(null)

  const phaseLabel = useMemo(() => {
    if (!activeChallenge) return 'Sin reto'
    if (activeChallenge.status === 'active') return 'Fase de participación'
    if (activeChallenge.status === 'voting') return 'Fase de votación'
    if (activeChallenge.status === 'completed') return 'Podio'
    return activeChallenge.status
  }, [activeChallenge])

  const deadlineLabel = useMemo(() => {
    if (!activeChallenge) return 'Sin fecha'
    const targetAt =
      activeChallenge.status === 'voting'
        ? activeChallenge.votingExpiresAt || activeChallenge.expiresAt
        : activeChallenge.expiresAt
    return remainingLabel(targetAt)
  }, [activeChallenge])

  const myRank = useMemo(() => {
    if (!user) return 0
    const rank = ranking.findIndex((entry) => entry.userId === user.id)
    return rank >= 0 ? rank + 1 : 0
  }, [ranking, user])

  const challengeStats = useMemo(() => {
    const totalSubmissions = submissions.length
    const totalVotes = submissions.reduce((acc, submission) => acc + submission.voteCount, 0)
    const mySubmissions = submissions.filter((submission) => submission.userId === user?.id).length
    const myVotes = submissions.filter((submission) => submission.votedByMe).length
    return { totalSubmissions, totalVotes, mySubmissions, myVotes }
  }, [submissions, user?.id])

  const visibleSubmissions = useMemo(() => {
    let filtered = submissions
    if (submissionFilter === 'mine') {
      filtered = filtered.filter((submission) => submission.userId === user?.id)
    } else if (submissionFilter === 'unvoted') {
      filtered = filtered.filter((submission) => !submission.votedByMe)
    }
    const query = submissionSearch.trim().toLowerCase()
    if (query) {
      filtered = filtered.filter((submission) => {
        return (
          submission.ownerName.toLowerCase().includes(query) ||
          submission.textContent.toLowerCase().includes(query) ||
          submission.userId.toLowerCase().includes(query)
        )
      })
    }
    return [...filtered].sort((a, b) => {
      if (submissionSort === 'new') {
        return toMillis(b.createdAt) - toMillis(a.createdAt)
      }
      if (b.voteCount !== a.voteCount) return b.voteCount - a.voteCount
      return toMillis(b.createdAt) - toMillis(a.createdAt)
    })
  }, [submissionFilter, submissionSearch, submissionSort, submissions, user?.id])

  const loadChallenge = useCallback(async () => {
    if (!user) {
      setLoading(false)
      return
    }
    if (isUbuntuPrimary) {
      setLoading(true)
      setError(null)
      try {
        const bundle = await loadUbuntuChallengesBundle(user.id)
        setActiveChallenge(bundle.activeChallenge)
        setChallengeMeta(bundle.challengeMeta)
        setSubmissions(bundle.submissions)
        setWinnerProfiles(bundle.winnerProfiles)
        setRanking(bundle.ranking)
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo cargar retos'))
      } finally {
        setLoading(false)
      }
      return
    }
    if (!supabase) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)

    try {
      const rpcActiveResult = await supabase.rpc('get_or_generate_active_challenge')
      let activeRow = ((rpcActiveResult.data ?? []) as GenericRow[])[0] ?? null

      if (!activeRow) {
        const fallbackResult = await supabase
          .from('active_challenges')
          .select('*')
          .in('status', ['active', 'voting', 'completed'])
          .order('created_at', { ascending: false })
          .limit(1)

        if (fallbackResult.error) {
          if (rpcActiveResult.error) throw rpcActiveResult.error
          throw fallbackResult.error
        }

        activeRow = ((fallbackResult.data ?? []) as GenericRow[])[0] ?? null
      }

      if (!activeRow) {
        setActiveChallenge(null)
        setChallengeMeta(null)
        setSubmissions([])
        setWinnerProfiles([])
        setLoading(false)
        return
      }

      const active: ActiveChallenge = {
        id: textOf(activeRow, ['id'], ''),
        challengeId: textOf(activeRow, ['challenge_id'], ''),
        status: textOf(activeRow, ['status'], 'active'),
        expiresAt: textOf(activeRow, ['expires_at'], ''),
        votingExpiresAt: textOf(activeRow, ['voting_expires_at'], ''),
        winnerIds: toStringArray(activeRow.winner_ids),
        createdAt: textOf(activeRow, ['created_at'], ''),
      }
      setActiveChallenge(active)

      if (active.winnerIds.length > 0) {
        const { data: winnerRows } = await supabase.from('profiles').select('*').in('id', active.winnerIds)
        const winnerById = new Map<string, string>()
        ;((winnerRows ?? []) as GenericRow[]).forEach((row) => {
          const id = textOf(row, ['id'], '')
          if (!id) return
          winnerById.set(
            id,
            textOf(row, ['display_name', 'displayName', 'full_name', 'username'], 'Usuario'),
          )
        })
        setWinnerProfiles(
          active.winnerIds.map((id) => ({
            id,
            name: winnerById.get(id) ?? id,
          })),
        )
      } else {
        setWinnerProfiles([])
      }

      if (active.challengeId) {
        const { data: metaRows } = await supabase
          .from('challenges_master')
          .select('*')
          .eq('id', active.challengeId)
          .limit(1)
        const metaRow = ((metaRows ?? []) as GenericRow[])[0]
        if (metaRow) {
          setChallengeMeta({
            id: textOf(metaRow, ['id'], ''),
            title: textOf(metaRow, ['title'], 'Reto'),
            description: textOf(metaRow, ['description'], ''),
            challengeType: textOf(metaRow, ['challenge_type'], 'text'),
            difficulty: textOf(metaRow, ['difficulty'], 'medium'),
          })
        } else {
          setChallengeMeta(null)
        }
      } else {
        setChallengeMeta(null)
      }

      let mappedSubmissions: ChallengeSubmission[] = []
      const { data: rpcSubmissions, error: rpcSubmissionsError } = await supabase.rpc(
        'get_challenge_submissions_voted',
        { p_active_challenge_id: active.id },
      )
      if (!rpcSubmissionsError) {
        mappedSubmissions = ((rpcSubmissions ?? []) as GenericRow[]).map((row) => ({
          id: textOf(row, ['id'], ''),
          activeChallengeId: textOf(row, ['active_challenge_id'], ''),
          userId: textOf(row, ['user_id'], ''),
          contentUrl: textOf(row, ['content_url'], ''),
          textContent: textOf(row, ['text_content'], ''),
          voteCount: Number(row.vote_count ?? 0),
          createdAt: textOf(row, ['created_at'], ''),
          votedByMe: Boolean(row.voted_by_me),
          ownerName: textOf(row, ['owner_name', 'user_name'], ''),
        }))
      } else {
        const { data: submissionRows, error: fallbackError } = await supabase
          .from('challenge_submissions')
          .select('*')
          .eq('active_challenge_id', active.id)
          .order('vote_count', { ascending: false })
          .order('created_at', { ascending: false })
        if (fallbackError) throw fallbackError

        const raw = (submissionRows ?? []) as GenericRow[]
        const userIds = Array.from(new Set(raw.map((row) => textOf(row, ['user_id'], '')).filter(Boolean)))
        const profileNames = new Map<string, string>()
        if (userIds.length > 0) {
          const { data: profileRows } = await supabase.from('profiles').select('*').in('id', userIds)
          ;((profileRows ?? []) as GenericRow[]).forEach((profile) => {
            const id = textOf(profile, ['id'], '')
            if (!id) return
            profileNames.set(
              id,
              textOf(profile, ['display_name', 'displayName', 'full_name', 'username'], 'Usuario'),
            )
          })
        }

        const submissionIds = raw.map((row) => textOf(row, ['id'], '')).filter(Boolean)
        const votedSet = new Set<string>()
        if (submissionIds.length > 0) {
          const { data: voteRows } = await supabase
            .from('challenge_votes')
            .select('submission_id')
            .eq('voter_id', user.id)
            .eq('active_challenge_id', active.id)
            .in('submission_id', submissionIds)
          ;((voteRows ?? []) as GenericRow[]).forEach((vote) => {
            const id = textOf(vote, ['submission_id'], '')
            if (id) votedSet.add(id)
          })
        }

        mappedSubmissions = raw.map((row) => {
          const id = textOf(row, ['id'], '')
          const ownerId = textOf(row, ['user_id'], '')
          return {
            id,
            activeChallengeId: textOf(row, ['active_challenge_id'], ''),
            userId: ownerId,
            contentUrl: textOf(row, ['content_url'], ''),
            textContent: textOf(row, ['text_content'], ''),
            voteCount: Number(row.vote_count ?? 0),
            createdAt: textOf(row, ['created_at'], ''),
            votedByMe: votedSet.has(id),
            ownerName: profileNames.get(ownerId) ?? ownerId,
          } satisfies ChallengeSubmission
        })
      }

      setSubmissions(mappedSubmissions)
      const rankingMap = new Map<string, ChallengeRankingRow>()
      mappedSubmissions.forEach((submission) => {
        const current = rankingMap.get(submission.userId) ?? {
          userId: submission.userId,
          ownerName: submission.ownerName || submission.userId,
          totalVotes: 0,
          submissions: 0,
        }
        current.totalVotes += submission.voteCount
        current.submissions += 1
        rankingMap.set(submission.userId, current)
      })
      setRanking(
        Array.from(rankingMap.values()).sort((a, b) => {
          if (a.totalVotes !== b.totalVotes) return b.totalVotes - a.totalVotes
          return b.submissions - a.submissions
        }),
      )
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar retos'))
    } finally {
      setLoading(false)
    }
  }, [user])

  useEffect(() => {
    void loadChallenge()
  }, [loadChallenge])

  useEffect(() => {
    if (isUbuntuPrimary) {
      return subscribeUbuntuRealtime({
        channel: 'challenge:live',
        onEvent: () => {
          void loadChallenge()
        },
      })
    }
    const client = supabase
    if (!client || !user) return
    const channel = client
      .channel('challenges-live')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'active_challenges' }, () => {
        void loadChallenge()
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'challenge_submissions' }, () => {
        void loadChallenge()
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'challenge_votes' }, () => {
        void loadChallenge()
      })
      .subscribe()

    return () => {
      void client.removeChannel(channel)
    }
  }, [loadChallenge, user])

  async function submitTextAnswer() {
    if ((!supabase && !isUbuntuPrimary) || !user || !activeChallenge || !challengeMeta || submitting) return
    const text = textAnswer.trim()
    if (!text) return
    setSubmitting(true)
    setError(null)
    try {
      const payload: Record<string, unknown> = {
        challenge_id: challengeMeta.id,
        active_challenge_id: activeChallenge.id,
        user_id: user.id,
        text_content: text,
      }
      if (isUbuntuPrimary) {
        await insertUbuntuChallengeSubmission(payload)
      } else {
        const { error: insertError } = await supabase!.from('challenge_submissions').insert(payload)
        if (insertError) throw insertError
      }
      setTextAnswer('')
      await loadChallenge()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar respuesta'))
    } finally {
      setSubmitting(false)
    }
  }

  async function submitMediaAnswer(file: File | null) {
    if ((!supabase && !isUbuntuPrimary) || !user || !activeChallenge || !challengeMeta || !file || uploadingFile) return
    setUploadingFile(true)
    setError(null)
    try {
      const mediaType = inferSubmissionMediaType(file)
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const path = `${user.id}/${activeChallenge.id}/${Date.now()}_${mediaType}_${safeName}`
      let publicUrl = ''
      if (isUbuntuPrimary) {
        const uploaded = await uploadFallbackFile('challenges', file, path)
        publicUrl = uploaded.publicUrl || uploaded.url
      } else {
        const { error: uploadError } = await supabase!.storage
          .from('challenges')
          .upload(path, file, { contentType: file.type || undefined })
        if (uploadError) throw uploadError
        const { data } = supabase!.storage.from('challenges').getPublicUrl(path)
        publicUrl = data.publicUrl
      }

      const payload: Record<string, unknown> = {
        challenge_id: challengeMeta.id,
        active_challenge_id: activeChallenge.id,
        user_id: user.id,
        content_url: publicUrl,
      }
      if (isUbuntuPrimary) {
        await insertUbuntuChallengeSubmission(payload)
      } else {
        const { error: insertError } = await supabase!.from('challenge_submissions').insert(payload)
        if (insertError) throw insertError
      }
      await loadChallenge()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar archivo del reto'))
    } finally {
      setUploadingFile(false)
    }
  }

  async function toggleVote(submissionId: string) {
    if ((!supabase && !isUbuntuPrimary) || !user || !activeChallenge || !submissionId) return
    try {
      await tryVoteRpc(submissionId)
      await loadChallenge()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo votar en el reto'))
    }
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

  if (!supabase && !isUbuntuPrimary) {
    return <StatusView title="Backend no configurado" description="Configura VITE_FALLBACK_API_URL para retos en Ubuntu." />
  }

  return (
    <section className="section-stack">
      <header className="section-header">
        <div className="row-between">
          <h2>Retos</h2>
          <Link to="/app/challenges/ranking" className="inline-link">
            Ver ranking global
          </Link>
        </div>
        <p>Reto activo compartido entre APK y web, con votación en tiempo real.</p>
      </header>

      {loading ? (
        <StatusView title="Cargando retos" description="Espera un momento..." />
      ) : error ? (
        <StatusView title="Retos no disponible" description={error} />
      ) : !activeChallenge ? (
        <StatusView title="Sin reto activo" description="No hay reto disponible por ahora." />
      ) : (
        <>
          <article className="surface-card section-stack">
            <div className="row-between">
              <h3>{challengeMeta?.title ?? 'Reto en curso'}</h3>
              <span className="folder-tag">{phaseLabel}</span>
            </div>
            <p>{challengeMeta?.description || 'Participa en el reto actual para sumar visibilidad.'}</p>
            <div className="row-actions">
              <span className="folder-tag">Tipo: {challengeMeta?.challengeType ?? 'text'}</span>
              <span className="folder-tag">Dificultad: {challengeMeta?.difficulty ?? 'medium'}</span>
              <span className="folder-tag">Inicio: {formatDateTime(activeChallenge.createdAt)}</span>
              <span className="folder-tag">Participación hasta: {formatDateTime(activeChallenge.expiresAt)}</span>
              <span className="folder-tag">Tiempo restante: {deadlineLabel}</span>
              {activeChallenge.votingExpiresAt ? (
                <span className="folder-tag">Votación hasta: {formatDateTime(activeChallenge.votingExpiresAt)}</span>
              ) : null}
            </div>
            {activeChallenge.status === 'completed' && winnerProfiles.length > 0 ? (
              <div className="row-actions">
                <span className="folder-tag">Ganadores:</span>
                {winnerProfiles.map((winner) => {
                  const openingChat = openingChatUserId === winner.id
                  const isMe = winner.id === user?.id
                  return (
                    <span key={winner.id} className="row-actions">
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
            ) : null}
          </article>

          <article className="surface-card section-stack">
            <h3>Ranking / Podio</h3>
            {myRank > 0 ? <p>Tu posición actual: #{myRank}</p> : null}
            {ranking.length === 0 ? (
              <p>Sin ranking todavía.</p>
            ) : (
              <div className="list-stack">
                {ranking.slice(0, 10).map((entry, index) => (
                  <div key={entry.userId} className="surface-subcard row-between">
                    <div>
                      <strong>
                        #{index + 1} {entry.ownerName}
                      </strong>
                      <small>{entry.submissions} participaciones</small>
                    </div>
                    <span className="folder-tag">{entry.totalVotes} votos</span>
                  </div>
                ))}
              </div>
            )}
          </article>

          <article className="surface-card section-stack">
            <h3>Resumen del reto</h3>
            <div className="row-actions">
              <span className="folder-tag">Participaciones: {challengeStats.totalSubmissions}</span>
              <span className="folder-tag">Votos totales: {challengeStats.totalVotes}</span>
              <span className="folder-tag">Tus participaciones: {challengeStats.mySubmissions}</span>
              <span className="folder-tag">Tus votos: {challengeStats.myVotes}</span>
            </div>
          </article>

          <article className="surface-card section-stack">
            <h3>Enviar participación</h3>
            {challengeMeta?.challengeType === 'text' ? (
              <div className="composer">
                <input
                  className="text-input"
                  placeholder="Escribe tu respuesta..."
                  value={textAnswer}
                  onChange={(event) => setTextAnswer(event.target.value)}
                />
                <button className="primary-btn" type="button" disabled={submitting} onClick={() => void submitTextAnswer()}>
                  {submitting ? 'Enviando...' : 'Enviar'}
                </button>
              </div>
            ) : (
              <label className="primary-btn file-pick-btn">
                {uploadingFile ? 'Subiendo...' : 'Subir respuesta'}
                <input
                  type="file"
                  hidden
                  accept={challengeMeta?.challengeType === 'audio' ? 'audio/*' : 'image/*,video/*'}
                  onChange={(event) => {
                    const file = event.target.files?.[0] ?? null
                    void submitMediaAnswer(file)
                    event.currentTarget.value = ''
                  }}
                />
              </label>
            )}
          </article>

          <section className="section-stack">
            <div className="row-between">
              <h3>Participaciones ({visibleSubmissions.length})</h3>
              <div className="row-actions">
                <button
                  className={submissionFilter === 'all' ? 'chip-btn active' : 'chip-btn'}
                  type="button"
                  onClick={() => setSubmissionFilter('all')}
                >
                  Todas
                </button>
                <button
                  className={submissionFilter === 'mine' ? 'chip-btn active' : 'chip-btn'}
                  type="button"
                  onClick={() => setSubmissionFilter('mine')}
                >
                  Mías
                </button>
                <button
                  className={submissionFilter === 'unvoted' ? 'chip-btn active' : 'chip-btn'}
                  type="button"
                  onClick={() => setSubmissionFilter('unvoted')}
                >
                  Sin votar
                </button>
                <button
                  className={submissionSort === 'top' ? 'chip-btn active' : 'chip-btn'}
                  type="button"
                  onClick={() => setSubmissionSort('top')}
                >
                  Top
                </button>
                <button
                  className={submissionSort === 'new' ? 'chip-btn active' : 'chip-btn'}
                  type="button"
                  onClick={() => setSubmissionSort('new')}
                >
                  Nuevas
                </button>
              </div>
            </div>
            <div className="composer">
              <input
                className="text-input"
                placeholder="Buscar por usuario, texto o ID..."
                value={submissionSearch}
                onChange={(event) => setSubmissionSearch(event.target.value)}
              />
              {submissionSearch.trim() ? (
                <button className="ghost-btn" type="button" onClick={() => setSubmissionSearch('')}>
                  Limpiar
                </button>
              ) : null}
            </div>
            {visibleSubmissions.length === 0 ? (
              <p>No hay participaciones todavía.</p>
            ) : (
              <div className="list-stack">
                {visibleSubmissions.map((submission) => {
                  const mine = submission.userId === user?.id
                  const votingOpen = activeChallenge.status === 'voting'
                  const canVote = votingOpen && !mine
                  const openingChat = openingChatUserId === submission.userId
                  return (
                    <article key={submission.id} className="surface-card section-stack">
                      <div className="row-between">
                        <strong>{submission.ownerName || submission.userId}</strong>
                        <small>{formatDateTime(submission.createdAt)}</small>
                      </div>
                      {submission.textContent ? <p>{submission.textContent}</p> : null}
                      {submission.contentUrl ? (
                        submission.contentUrl.match(/\.(mp4|webm|mov|m4v)$/i) ? (
                          <video controls preload="metadata" src={submission.contentUrl} className="video-frame" />
                        ) : (
                          <img src={submission.contentUrl} alt="Participación reto" className="story-media" />
                        )
                      ) : null}
                      <div className="row-actions">
                        {mine ? <span className="folder-tag">Tu participación</span> : null}
                        {submission.votedByMe ? <span className="folder-tag">Votaste</span> : null}
                        <span className="folder-tag">Votos: {submission.voteCount}</span>
                        <button
                          className="ghost-btn"
                          type="button"
                          disabled={!canVote}
                          onClick={() => void toggleVote(submission.id)}
                        >
                          {!votingOpen
                            ? 'Votación cerrada'
                            : mine
                              ? 'No votas tu post'
                              : submission.votedByMe
                                ? 'Quitar voto'
                                : 'Votar'}
                        </button>
                        {!mine ? (
                          <>
                            <Link className="mini-action-btn" to={`/app/profile/${submission.userId}`}>
                              Perfil
                            </Link>
                            <button
                              className="mini-action-btn"
                              type="button"
                              disabled={openingChat}
                              onClick={() => void openDirectChat(submission.userId)}
                            >
                              {openingChat ? 'Abriendo...' : 'Mensaje'}
                            </button>
                          </>
                        ) : null}
                      </div>
                    </article>
                  )
                })}
              </div>
            )}
          </section>
        </>
      )}
    </section>
  )
}
