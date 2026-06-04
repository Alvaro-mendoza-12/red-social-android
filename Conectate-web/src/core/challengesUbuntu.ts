import { fallbackApiJson } from './auth/fallbackAuth'
import { callUbuntuRpc } from './fallbackRpc'
import { loadUbuntuRestTable } from './fallbackData'
import { textOf, type GenericRow } from './utils'

export type UbuntuActiveChallenge = {
  id: string
  challengeId: string
  status: string
  expiresAt: string
  votingExpiresAt: string
  winnerIds: string[]
  createdAt: string
}

export type UbuntuChallengeMaster = {
  id: string
  title: string
  description: string
  challengeType: string
  difficulty: string
}

export type UbuntuChallengeSubmission = {
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

function toStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.map((entry) => String(entry).trim()).filter(Boolean)
}

function mapActive(row: GenericRow | null): UbuntuActiveChallenge | null {
  if (!row) return null
  return {
    id: textOf(row, ['id'], ''),
    challengeId: textOf(row, ['challenge_id', 'challengeId'], ''),
    status: textOf(row, ['status'], 'active'),
    expiresAt: textOf(row, ['expires_at', 'expiresAt'], ''),
    votingExpiresAt: textOf(row, ['voting_expires_at', 'votingExpiresAt'], ''),
    winnerIds: toStringArray(row.winner_ids ?? row.winnerIds),
    createdAt: textOf(row, ['created_at', 'createdAt'], ''),
  }
}

export async function loadUbuntuChallengesBundle(userId: string) {
  const activeRows = await callUbuntuRpc<GenericRow[]>('get_or_generate_active_challenge', {})
  const activeRow = Array.isArray(activeRows) ? activeRows[0] ?? null : null
  const active = mapActive(activeRow)
  if (!active) {
    return {
      activeChallenge: null as UbuntuActiveChallenge | null,
      challengeMeta: null as UbuntuChallengeMaster | null,
      submissions: [] as UbuntuChallengeSubmission[],
      winnerProfiles: [] as { id: string; name: string }[],
      ranking: [] as { userId: string; ownerName: string; totalVotes: number; submissions: number }[],
    }
  }

  let challengeMeta: UbuntuChallengeMaster | null = null
  if (active.challengeId) {
    const metaRows = await loadUbuntuRestTable(
      'challenges_master',
      `?id=eq.${encodeURIComponent(active.challengeId)}&limit=1`,
    )
    const metaRow = metaRows[0]
    if (metaRow) {
      challengeMeta = {
        id: textOf(metaRow, ['id'], ''),
        title: textOf(metaRow, ['title'], 'Reto'),
        description: textOf(metaRow, ['description'], ''),
        challengeType: textOf(metaRow, ['challenge_type', 'challengeType'], 'text'),
        difficulty: textOf(metaRow, ['difficulty'], 'medium'),
      }
    }
  }

  const votedRows = await callUbuntuRpc<GenericRow[]>('get_challenge_submissions_voted', {
    p_active_challenge_id: active.id,
  })
  const submissions: UbuntuChallengeSubmission[] = (votedRows ?? []).map((row) => ({
    id: textOf(row, ['id'], ''),
    activeChallengeId: textOf(row, ['active_challenge_id', 'activeChallengeId'], ''),
    userId: textOf(row, ['user_id', 'userId'], ''),
    contentUrl: textOf(row, ['content_url', 'contentUrl'], ''),
    textContent: textOf(row, ['text_content', 'textContent'], ''),
    voteCount: Number(row.vote_count ?? row.voteCount ?? 0),
    createdAt: textOf(row, ['created_at', 'createdAt'], ''),
    votedByMe: Boolean(row.voted_by_me ?? row.votedByMe),
    ownerName: textOf(row, ['owner_name', 'ownerName', 'user_name'], 'Usuario'),
  }))

  const winnerProfiles: { id: string; name: string }[] = []
  if (active.winnerIds.length > 0) {
    const profiles = await loadUbuntuRestTable('profiles', `?limit=5000`)
    const byId = new Map(profiles.map((profile) => [textOf(profile, ['id'], ''), profile]))
    active.winnerIds.forEach((id) => {
      const profile = byId.get(id)
      winnerProfiles.push({
        id,
        name: textOf(profile ?? {}, ['display_name', 'displayName', 'full_name', 'username'], id),
      })
    })
  }

  const rankingMap = new Map<string, { userId: string; ownerName: string; totalVotes: number; submissions: number }>()
  submissions.forEach((submission) => {
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

  const ranking = Array.from(rankingMap.values()).sort((a, b) => {
    if (a.totalVotes !== b.totalVotes) return b.totalVotes - a.totalVotes
    return b.submissions - a.submissions
  })

  return { activeChallenge: active, challengeMeta, submissions, winnerProfiles, ranking }
}

export async function insertUbuntuChallengeSubmission(payload: Record<string, unknown>) {
  return fallbackApiJson<GenericRow[]>('/rest/v1/challenge_submissions', {
    method: 'POST',
    headers: { Prefer: 'return=representation' },
    body: JSON.stringify(payload),
  })
}
