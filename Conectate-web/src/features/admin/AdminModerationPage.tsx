import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../auth/AuthProvider'

type VideoReport = {
  id: string
  videoId: string
  videoOwnerId: string
  videoOwnerName: string
  videoTitle: string
  reporterId: string
  reporterName: string
  reason: string
  details: string
  status: string
  warnSent: boolean
  strikeApplied: boolean
  actionNote: string
  reviewedAt: string
  createdAt: string
}

type UserReport = {
  id: string
  reporterId: string
  reporterName: string
  reportedUserId: string
  reportedUserName: string
  reason: string
  description: string
  evidenceUrl: string
  status: string
  createdAt: string
}

type SuspendedProfile = {
  id: string
  displayName: string
  username: string
  email: string
  suspendedUntil: string
  suspensionEndDate: string
  suspensionReason: string
  suspensionCount: number
}

type ModerationEvent = {
  id: string
  actorId: string
  context: string
  contentRef: string
  verdict: string
  metadataPreview: string
  createdAt: string
}

type BulkFailure = {
  id: string
  message: string
}

type BulkRunSummary = {
  total: number
  success: number
  failures: BulkFailure[]
}

function mapVideoReport(row: GenericRow): VideoReport {
  return {
    id: textOf(row, ['id'], ''),
    videoId: textOf(row, ['video_id'], ''),
    videoOwnerId: textOf(row, ['video_owner_id'], ''),
    videoOwnerName: textOf(row, ['video_owner_name'], ''),
    videoTitle: textOf(row, ['video_title'], ''),
    reporterId: textOf(row, ['reporter_id'], ''),
    reporterName: textOf(row, ['reporter_name'], ''),
    reason: textOf(row, ['reason'], ''),
    details: textOf(row, ['details'], ''),
    status: textOf(row, ['status'], 'pendiente'),
    warnSent: Boolean(row.warn_sent),
    strikeApplied: Boolean(row.strike_applied),
    actionNote: textOf(row, ['action_note'], ''),
    reviewedAt: textOf(row, ['reviewed_at'], ''),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

function mapUserReport(row: GenericRow): UserReport {
  return {
    id: textOf(row, ['id'], ''),
    reporterId: textOf(row, ['reporter_id'], ''),
    reporterName: textOf(row, ['reporter_name'], ''),
    reportedUserId: textOf(row, ['reported_user_id'], ''),
    reportedUserName: textOf(row, ['reported_user_name'], ''),
    reason: textOf(row, ['reason'], ''),
    description: textOf(row, ['description'], ''),
    evidenceUrl: textOf(row, ['evidence_url'], ''),
    status: textOf(row, ['status'], 'pendiente'),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

function mapSuspendedProfile(row: GenericRow): SuspendedProfile {
  return {
    id: textOf(row, ['id'], ''),
    displayName: textOf(row, ['display_name'], ''),
    username: textOf(row, ['username'], ''),
    email: textOf(row, ['email'], ''),
    suspendedUntil: textOf(row, ['suspended_until'], ''),
    suspensionEndDate: textOf(row, ['suspension_end_date'], ''),
    suspensionReason: textOf(row, ['suspension_reason'], ''),
    suspensionCount:
      typeof row.suspension_count === 'number'
        ? row.suspension_count
        : Number(row.suspension_count ?? 0) || 0,
  }
}

function mapModerationEvent(row: GenericRow): ModerationEvent {
  const metadataRaw = row.metadata == null ? '' : typeof row.metadata === 'string' ? row.metadata : JSON.stringify(row.metadata)
  return {
    id: String(row.id ?? ''),
    actorId: textOf(row, ['actor_id'], ''),
    context: textOf(row, ['context'], ''),
    contentRef: textOf(row, ['content_ref'], ''),
    verdict: textOf(row, ['verdict'], 'allow'),
    metadataPreview: metadataRaw.length > 150 ? `${metadataRaw.slice(0, 150)}...` : metadataRaw,
    createdAt: textOf(row, ['created_at'], ''),
  }
}

const videoStatuses = [
  'pendiente',
  'en_revision',
  'finalizado',
  'revisado',
  'eliminado',
  'advertido',
  'rechazado',
] as const

const userStatuses = ['pendiente', 'en_proceso', 'finalizado', 'rechazado'] as const
const BULK_PARALLEL_CHUNK = 6

function chunkArray<T>(values: T[], size: number): T[][] {
  if (size <= 0) return [values]
  const chunks: T[][] = []
  for (let index = 0; index < values.length; index += size) {
    chunks.push(values.slice(index, index + size))
  }
  return chunks
}

function isMissingSchemaError(error: unknown): boolean {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return isMissingTableError(error) || message.includes('column') || message.includes('42703')
}

function isMissingFunctionError(error: unknown): boolean {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return (
    message.includes('could not find') ||
    message.includes('does not exist') ||
    message.includes('function') ||
    message.includes('42883')
  )
}

export function AdminModerationPage() {
  const { user } = useAuth()
  const [loading, setLoading] = useState(true)
  const [isAdmin, setIsAdmin] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [videoReports, setVideoReports] = useState<VideoReport[]>([])
  const [videoStatusById, setVideoStatusById] = useState<Record<string, string>>({})
  const [videoNoteById, setVideoNoteById] = useState<Record<string, string>>({})
  const [videoActionLoading, setVideoActionLoading] = useState<Record<string, boolean>>({})
  const [videoStatusFilter, setVideoStatusFilter] = useState('all')
  const [videoSearchQuery, setVideoSearchQuery] = useState('')
  const [videoSelectedById, setVideoSelectedById] = useState<Record<string, boolean>>({})
  const [videoBulkStatus, setVideoBulkStatus] = useState<string>('en_revision')
  const [videoBulkNote, setVideoBulkNote] = useState('')
  const [videoBulkLoading, setVideoBulkLoading] = useState(false)
  const [videoBulkWarning, setVideoBulkWarning] = useState(false)
  const [videoBulkDeleting, setVideoBulkDeleting] = useState(false)
  const [videoBulkSuspendDays, setVideoBulkSuspendDays] = useState(7)
  const [videoBulkSuspending, setVideoBulkSuspending] = useState(false)

  const [userReports, setUserReports] = useState<UserReport[]>([])
  const [userReportsAvailable, setUserReportsAvailable] = useState(true)
  const [userStatusById, setUserStatusById] = useState<Record<string, string>>({})
  const [userActionLoading, setUserActionLoading] = useState<Record<string, boolean>>({})
  const [userStatusFilter, setUserStatusFilter] = useState('all')
  const [userSearchQuery, setUserSearchQuery] = useState('')
  const [userSelectedById, setUserSelectedById] = useState<Record<string, boolean>>({})
  const [userBulkStatus, setUserBulkStatus] = useState<string>('en_proceso')
  const [userBulkLoading, setUserBulkLoading] = useState(false)
  const [userBulkDeleting, setUserBulkDeleting] = useState(false)
  const [userBulkSuspendDays, setUserBulkSuspendDays] = useState(7)
  const [userBulkSuspending, setUserBulkSuspending] = useState(false)

  const [suspendUserId, setSuspendUserId] = useState('')
  const [suspendDays, setSuspendDays] = useState(7)
  const [suspendDurationUnit, setSuspendDurationUnit] = useState<'minutes' | 'hours' | 'days' | 'years'>('days')
  const [suspendPermanent, setSuspendPermanent] = useState(false)
  const [suspendReason, setSuspendReason] = useState('')
  const [suspending, setSuspending] = useState(false)
  const [videoSuspendDaysById, setVideoSuspendDaysById] = useState<Record<string, number>>({})
  const [userSuspendDaysById, setUserSuspendDaysById] = useState<Record<string, number>>({})
  const [suspendedProfiles, setSuspendedProfiles] = useState<SuspendedProfile[]>([])
  const [suspendedSearchQuery, setSuspendedSearchQuery] = useState('')
  const [suspensionLoadingById, setSuspensionLoadingById] = useState<Record<string, boolean>>({})
  const [suspensionModuleAvailable, setSuspensionModuleAvailable] = useState(true)

  const [events, setEvents] = useState<ModerationEvent[]>([])
  const [eventsAvailable, setEventsAvailable] = useState(true)
  const [eventVerdictFilter, setEventVerdictFilter] = useState('all')
  const [eventSearchQuery, setEventSearchQuery] = useState('')
  const [bulkSummaryText, setBulkSummaryText] = useState<string | null>(null)
  const [bulkFailureDetails, setBulkFailureDetails] = useState<string[]>([])

  const filteredVideoReports = useMemo(() => {
    const query = videoSearchQuery.trim().toLowerCase()
    return videoReports.filter((report) => {
      if (videoStatusFilter !== 'all' && report.status !== videoStatusFilter) return false
      if (!query) return true
      return [
        report.videoTitle,
        report.videoId,
        report.videoOwnerName,
        report.videoOwnerId,
        report.reporterName,
        report.reporterId,
        report.reason,
      ]
        .join(' ')
        .toLowerCase()
        .includes(query)
    })
  }, [videoReports, videoSearchQuery, videoStatusFilter])

  const filteredUserReports = useMemo(() => {
    const query = userSearchQuery.trim().toLowerCase()
    return userReports.filter((report) => {
      if (userStatusFilter !== 'all' && report.status !== userStatusFilter) return false
      if (!query) return true
      return [report.reporterName, report.reportedUserName, report.reason, report.description]
        .join(' ')
        .toLowerCase()
        .includes(query)
    })
  }, [userReports, userSearchQuery, userStatusFilter])

  const filteredSuspendedProfiles = useMemo(() => {
    const query = suspendedSearchQuery.trim().toLowerCase()
    if (!query) return suspendedProfiles
    return suspendedProfiles.filter((profile) =>
      [profile.id, profile.displayName, profile.username, profile.email, profile.suspensionReason]
        .join(' ')
        .toLowerCase()
        .includes(query),
    )
  }, [suspendedProfiles, suspendedSearchQuery])

  const filteredEvents = useMemo(() => {
    const query = eventSearchQuery.trim().toLowerCase()
    return events.filter((event) => {
      if (eventVerdictFilter !== 'all' && event.verdict !== eventVerdictFilter) return false
      if (!query) return true
      return [event.context, event.actorId, event.contentRef, event.metadataPreview]
        .join(' ')
        .toLowerCase()
        .includes(query)
    })
  }, [events, eventSearchQuery, eventVerdictFilter])

  const selectedVideoIds = useMemo(
    () => filteredVideoReports.filter((report) => videoSelectedById[report.id]).map((report) => report.id),
    [filteredVideoReports, videoSelectedById],
  )

  const selectedUserIds = useMemo(
    () => filteredUserReports.filter((report) => userSelectedById[report.id]).map((report) => report.id),
    [filteredUserReports, userSelectedById],
  )

  const selectedVideoOwnerIds = useMemo(() => {
    return Array.from(
      new Set(
        filteredVideoReports
          .filter((report) => videoSelectedById[report.id])
          .map((report) => report.videoOwnerId.trim())
          .filter(Boolean),
      ),
    )
  }, [filteredVideoReports, videoSelectedById])

  const selectedReportedUserIds = useMemo(() => {
    return Array.from(
      new Set(
        filteredUserReports
          .filter((report) => userSelectedById[report.id])
          .map((report) => report.reportedUserId.trim())
          .filter(Boolean),
      ),
    )
  }, [filteredUserReports, userSelectedById])

  const loadAdminState = useCallback(async () => {
    if (!supabase || !user) {
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      const { data, error: rpcError } = await supabase.rpc('is_current_user_admin', { p_user_id: user.id })
      if (!rpcError) {
        setIsAdmin(Boolean(data))
        return
      }

      const { data: profileRows, error: profileError } = await supabase
        .from('profiles')
        .select('is_admin,email')
        .eq('id', user.id)
        .limit(1)
      if (profileError) throw profileError
      const profile = ((profileRows ?? []) as GenericRow[])[0] ?? {}
      const email = textOf(profile, ['email'], '').toLowerCase()
      setIsAdmin(
        Boolean(profile.is_admin) ||
          email === 'gamergamerff017@gmail.com' ||
          email === 'alvaromendozamarin004@gmail.com',
      )
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo validar admin'))
    } finally {
      setLoading(false)
    }
  }, [user])

  const loadVideoReports = useCallback(async () => {
    if (!supabase || !user || !isAdmin) return
    try {
      const { data, error: reportsError } = await supabase
        .from('video_reports')
        .select('*')
        .order('created_at', { ascending: false })
        .limit(120)
      if (reportsError) throw reportsError
      const mapped = ((data ?? []) as GenericRow[]).map(mapVideoReport)
      setVideoReports(mapped)
      setVideoStatusById(mapped.reduce<Record<string, string>>((acc, row) => ({ ...acc, [row.id]: row.status }), {}))
      setVideoNoteById(mapped.reduce<Record<string, string>>((acc, row) => ({ ...acc, [row.id]: row.actionNote }), {}))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudieron cargar reportes de video'))
    }
  }, [isAdmin, user])

  const loadUserReports = useCallback(async () => {
    if (!supabase || !user || !isAdmin) return
    try {
      const { data, error: reportsError } = await supabase
        .from('reports')
        .select('*')
        .order('created_at', { ascending: false })
        .limit(120)
      if (reportsError) throw reportsError
      const mapped = ((data ?? []) as GenericRow[]).map(mapUserReport)
      setUserReportsAvailable(true)
      setUserReports(mapped)
      setUserStatusById(mapped.reduce<Record<string, string>>((acc, row) => ({ ...acc, [row.id]: row.status }), {}))
    } catch (caught) {
      if (isMissingSchemaError(caught)) {
        setUserReportsAvailable(false)
        setUserReports([])
        return
      }
      setError(friendlyError(caught, 'No se pudieron cargar reportes de usuario'))
    }
  }, [isAdmin, user])

  const loadSuspendedProfiles = useCallback(async () => {
    if (!supabase || !user || !isAdmin) return
    try {
      const { data, error: suspendError } = await supabase
        .from('profiles')
        .select('id,display_name,username,email,is_suspended,suspended_until,suspension_end_date,suspension_reason,suspension_count')
        .eq('is_suspended', true)
        .order('updated_at', { ascending: false })
        .limit(120)
      if (suspendError) throw suspendError
      setSuspensionModuleAvailable(true)
      setSuspendedProfiles(((data ?? []) as GenericRow[]).map(mapSuspendedProfile))
    } catch (caught) {
      if (isMissingSchemaError(caught)) {
        setSuspensionModuleAvailable(false)
        setSuspendedProfiles([])
        return
      }
      setError(friendlyError(caught, 'No se pudieron cargar suspensiones activas'))
    }
  }, [isAdmin, user])

  const loadEvents = useCallback(async () => {
    if (!supabase || !user || !isAdmin) return
    try {
      const { data, error: eventsError } = await supabase
        .from('moderation_events')
        .select('*')
        .order('created_at', { ascending: false })
        .limit(120)
      if (eventsError) throw eventsError
      setEventsAvailable(true)
      setEvents(((data ?? []) as GenericRow[]).map(mapModerationEvent))
    } catch (caught) {
      if (isMissingSchemaError(caught)) {
        setEventsAvailable(false)
        setEvents([])
        return
      }
      setError(friendlyError(caught, 'No se pudieron cargar eventos de moderación'))
    }
  }, [isAdmin, user])

  useEffect(() => {
    void loadAdminState()
  }, [loadAdminState])

  useEffect(() => {
    if (!isAdmin) return
    void Promise.all([loadVideoReports(), loadUserReports(), loadSuspendedProfiles(), loadEvents()])
  }, [isAdmin, loadEvents, loadSuspendedProfiles, loadUserReports, loadVideoReports])

  useEffect(() => {
    const client = supabase
    if (!client || !isAdmin) return
    const channel = client
      .channel('admin-moderation-live')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'video_reports' }, () => {
        void loadVideoReports()
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'reports' }, () => {
        void loadUserReports()
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'profiles' }, () => {
        void loadSuspendedProfiles()
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'moderation_events' }, () => {
        void loadEvents()
      })
      .subscribe()
    return () => {
      void client.removeChannel(channel)
    }
  }, [isAdmin, loadEvents, loadSuspendedProfiles, loadUserReports, loadVideoReports])

  useEffect(() => {
    const validIds = new Set(filteredVideoReports.map((report) => report.id))
    setVideoSelectedById((current) => {
      const next: Record<string, boolean> = {}
      for (const [id, selected] of Object.entries(current)) {
        if (selected && validIds.has(id)) next[id] = true
      }
      const same =
        Object.keys(next).length === Object.keys(current).length &&
        Object.entries(next).every(([id, selected]) => current[id] === selected)
      return same ? current : next
    })
  }, [filteredVideoReports])

  useEffect(() => {
    const validIds = new Set(filteredUserReports.map((report) => report.id))
    setUserSelectedById((current) => {
      const next: Record<string, boolean> = {}
      for (const [id, selected] of Object.entries(current)) {
        if (selected && validIds.has(id)) next[id] = true
      }
      const same =
        Object.keys(next).length === Object.keys(current).length &&
        Object.entries(next).every(([id, selected]) => current[id] === selected)
      return same ? current : next
    })
  }, [filteredUserReports])

  async function suspendUserAdvanced(
    targetUserId: string,
    options?: {
      durationValue?: number
      durationUnit?: 'minutes' | 'hours' | 'days' | 'years'
      permanent?: boolean
      reason?: string | null
    },
  ) {
    if (!supabase) return
    const userId = targetUserId.trim()
    if (!userId) return

    const durationValue = Math.max(1, Number(options?.durationValue ?? suspendDays) || 1)
    const durationUnit = options?.durationUnit ?? suspendDurationUnit
    const permanent = options?.permanent ?? suspendPermanent
    const reason = options?.reason ?? (suspendReason.trim() || null)

    const extendedResult = await supabase.rpc('secure_admin_set_user_suspension', {
      p_user_id: userId,
      p_suspend: true,
      p_days: durationValue,
      p_reason: reason,
      p_duration_value: durationValue,
      p_duration_unit: durationUnit,
      p_permanent: permanent,
    })
    if (!extendedResult.error) return

    if (!isMissingFunctionError(extendedResult.error)) {
      throw extendedResult.error
    }

    const fallbackResult = await supabase.rpc('secure_admin_set_user_suspension', {
      p_user_id: userId,
      p_suspend: true,
      p_days: durationValue,
      p_reason: reason,
    })
    if (fallbackResult.error) throw fallbackResult.error
  }

  async function updateVideoReport(reportId: string) {
    if (!supabase) return
    setVideoActionLoading((current) => ({ ...current, [reportId]: true }))
    setError(null)
    try {
      const { error: updateError } = await supabase.rpc('secure_admin_update_video_report_status', {
        p_report_id: reportId,
        p_status: videoStatusById[reportId] ?? 'pendiente',
        p_action_note: videoNoteById[reportId] ?? null,
      })
      if (updateError) throw updateError
      await loadVideoReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar estado del reporte'))
    } finally {
      setVideoActionLoading((current) => ({ ...current, [reportId]: false }))
    }
  }

  async function warnVideoOwner(reportId: string) {
    if (!supabase) return
    setVideoActionLoading((current) => ({ ...current, [reportId]: true }))
    setError(null)
    try {
      const { error: warnError } = await supabase.rpc('secure_admin_warn_video_owner_from_report', {
        p_report_id: reportId,
        p_message: videoNoteById[reportId] ?? null,
      })
      if (warnError) throw warnError
      await loadVideoReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo advertir al creador'))
    } finally {
      setVideoActionLoading((current) => ({ ...current, [reportId]: false }))
    }
  }

  async function deleteVideoReportContent(reportId: string) {
    if (!supabase) return
    setVideoActionLoading((current) => ({ ...current, [reportId]: true }))
    setError(null)
    try {
      const { error: deleteError } = await supabase.rpc('secure_admin_delete_reported_video', {
        p_report_id: reportId,
        p_action_note: videoNoteById[reportId] ?? null,
      })
      if (deleteError) throw deleteError
      await loadVideoReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo eliminar el video reportado'))
    } finally {
      setVideoActionLoading((current) => ({ ...current, [reportId]: false }))
    }
  }

  async function suspendFromVideoReport(reportId: string) {
    if (!supabase) return
    setVideoActionLoading((current) => ({ ...current, [reportId]: true }))
    setError(null)
    try {
      const durationValue = Math.max(1, videoSuspendDaysById[reportId] ?? suspendDays)
      const { error: suspendError } = await supabase.rpc('secure_suspend_video_owner_from_report', {
        p_report_id: reportId,
        p_days: durationValue,
      })
      if (suspendError) {
        if (!isMissingFunctionError(suspendError)) throw suspendError
        const report = videoReports.find((entry) => entry.id === reportId)
        if (!report?.videoOwnerId) throw suspendError
        await suspendUserAdvanced(report.videoOwnerId, {
          durationValue,
          durationUnit: 'days',
          permanent: false,
          reason: 'Reincidencia en reportes de video',
        })
      }
      await Promise.all([loadVideoReports(), loadSuspendedProfiles()])
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo suspender al creador desde el reporte'))
    } finally {
      setVideoActionLoading((current) => ({ ...current, [reportId]: false }))
    }
  }

  async function applySuspension() {
    if (!supabase || !suspendUserId.trim() || suspending) return
    setSuspending(true)
    setError(null)
    try {
      await suspendUserAdvanced(suspendUserId)
      setSuspendReason('')
      await loadSuspendedProfiles()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo suspender al usuario'))
    } finally {
      setSuspending(false)
    }
  }

  async function removeSuspension(userId = suspendUserId) {
    if (!supabase || !userId.trim()) return
    setSuspending(true)
    setSuspensionLoadingById((current) => ({ ...current, [userId]: true }))
    setError(null)
    try {
      const { error: suspendError } = await supabase.rpc('secure_admin_set_user_suspension', {
        p_user_id: userId.trim(),
        p_suspend: false,
      })
      if (suspendError) throw suspendError
      await loadSuspendedProfiles()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo levantar suspensión'))
    } finally {
      setSuspending(false)
      setSuspensionLoadingById((current) => ({ ...current, [userId]: false }))
    }
  }

  async function updateUserReport(reportId: string) {
    if (!supabase) return
    setUserActionLoading((current) => ({ ...current, [reportId]: true }))
    setError(null)
    try {
      const { error: updateError } = await supabase
        .from('reports')
        .update({ status: userStatusById[reportId] ?? 'pendiente' })
        .eq('id', reportId)
      if (updateError) throw updateError
      await loadUserReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar reporte de usuario'))
    } finally {
      setUserActionLoading((current) => ({ ...current, [reportId]: false }))
    }
  }

  async function deleteUserReport(reportId: string) {
    if (!supabase) return
    setUserActionLoading((current) => ({ ...current, [reportId]: true }))
    setError(null)
    try {
      const { error: deleteError } = await supabase.from('reports').delete().eq('id', reportId)
      if (deleteError) throw deleteError
      await loadUserReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo eliminar reporte de usuario'))
    } finally {
      setUserActionLoading((current) => ({ ...current, [reportId]: false }))
    }
  }

  async function suspendFromUserReport(reportId: string) {
    if (!supabase) return
    setUserActionLoading((current) => ({ ...current, [reportId]: true }))
    setError(null)
    try {
      const report = userReports.find((entry) => entry.id === reportId)
      if (!report?.reportedUserId) return
      const durationValue = Math.max(1, userSuspendDaysById[reportId] ?? suspendDays)
      await suspendUserAdvanced(report.reportedUserId, {
        durationValue,
        durationUnit: 'days',
        permanent: false,
        reason: report.reason || 'Reporte de usuario',
      })
      await loadSuspendedProfiles()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo suspender el usuario reportado'))
    } finally {
      setUserActionLoading((current) => ({ ...current, [reportId]: false }))
    }
  }

  function toggleSelectAllVideoReports(enabled: boolean) {
    setVideoSelectedById(() => {
      if (!enabled) return {}
      return filteredVideoReports.reduce<Record<string, boolean>>((acc, report) => {
        if (report.id) acc[report.id] = true
        return acc
      }, {})
    })
  }

  function toggleSelectAllUserReports(enabled: boolean) {
    setUserSelectedById(() => {
      if (!enabled) return {}
      return filteredUserReports.reduce<Record<string, boolean>>((acc, report) => {
        if (report.id) acc[report.id] = true
        return acc
      }, {})
    })
  }

  function applyBulkSummary(label: string, summary: BulkRunSummary) {
    setBulkSummaryText(`${label}: ${summary.success}/${summary.total} OK`)
    setBulkFailureDetails(
      summary.failures.slice(0, 8).map((failure) => `${failure.id}: ${failure.message}`),
    )
  }

  async function runBulkInChunks(
    ids: string[],
    worker: (id: string) => Promise<void>,
  ): Promise<BulkRunSummary> {
    const failures: BulkFailure[] = []
    for (const chunk of chunkArray(ids, BULK_PARALLEL_CHUNK)) {
      const results = await Promise.all(
        chunk.map(async (id) => {
          let lastError: unknown = null
          for (let attempt = 0; attempt < 2; attempt += 1) {
            try {
              await worker(id)
              return null
            } catch (caught) {
              lastError = caught
              if (attempt === 0) {
                await new Promise<void>((resolve) => {
                  window.setTimeout(() => resolve(), 180)
                })
              }
            }
          }
          const message =
            lastError instanceof Error
              ? lastError.message
              : String(lastError ?? 'error desconocido')
          return { id, message } satisfies BulkFailure
        }),
      )
      results.forEach((failure) => {
        if (failure) failures.push(failure)
      })
    }

    return {
      total: ids.length,
      success: Math.max(0, ids.length - failures.length),
      failures,
    }
  }

  async function applyBulkVideoStatus() {
    const client = supabase
    if (!client || selectedVideoIds.length === 0 || videoBulkLoading) return
    setVideoBulkLoading(true)
    setBulkSummaryText(null)
    setBulkFailureDetails([])
    setError(null)
    try {
      const note = videoBulkNote.trim() || null
      const summary = await runBulkInChunks(
        selectedVideoIds,
        async (reportId) => {
          const { error: updateError } = await client.rpc('secure_admin_update_video_report_status', {
            p_report_id: reportId,
            p_status: videoBulkStatus,
            p_action_note: note,
          })
          if (updateError) throw updateError
        },
      )
      applyBulkSummary('Estado masivo video', summary)
      if (summary.failures.length === 0) {
        setVideoSelectedById({})
        setVideoBulkNote('')
      } else {
        const failedSet = new Set(summary.failures.map((failure) => failure.id))
        setVideoSelectedById(
          filteredVideoReports.reduce<Record<string, boolean>>((acc, report) => {
            if (failedSet.has(report.id)) acc[report.id] = true
            return acc
          }, {}),
        )
        setError(`${summary.failures.length}/${summary.total} reportes fallaron en estado masivo`)
      }
      await loadVideoReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo aplicar estado masivo en reportes de video'))
    } finally {
      setVideoBulkLoading(false)
    }
  }

  async function warnSelectedVideoReports() {
    const client = supabase
    if (!client || selectedVideoIds.length === 0 || videoBulkWarning) return
    setVideoBulkWarning(true)
    setBulkSummaryText(null)
    setBulkFailureDetails([])
    setError(null)
    try {
      const note = videoBulkNote.trim() || null
      const summary = await runBulkInChunks(
        selectedVideoIds,
        async (reportId) => {
          const { error: warnError } = await client.rpc('secure_admin_warn_video_owner_from_report', {
            p_report_id: reportId,
            p_message: note,
          })
          if (warnError) throw warnError
        },
      )
      applyBulkSummary('Advertencia masiva video', summary)
      if (summary.failures.length === 0) {
        setVideoSelectedById({})
      } else {
        const failedSet = new Set(summary.failures.map((failure) => failure.id))
        setVideoSelectedById(
          filteredVideoReports.reduce<Record<string, boolean>>((acc, report) => {
            if (failedSet.has(report.id)) acc[report.id] = true
            return acc
          }, {}),
        )
        setError(`${summary.failures.length}/${summary.total} reportes fallaron en advertencia masiva`)
      }
      await loadVideoReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo advertir reportes de video seleccionados'))
    } finally {
      setVideoBulkWarning(false)
    }
  }

  async function deleteSelectedReportedVideos() {
    const client = supabase
    if (!client || selectedVideoIds.length === 0 || videoBulkDeleting) return
    setVideoBulkDeleting(true)
    setBulkSummaryText(null)
    setBulkFailureDetails([])
    setError(null)
    try {
      const note = videoBulkNote.trim() || null
      const summary = await runBulkInChunks(
        selectedVideoIds,
        async (reportId) => {
          const { error: deleteError } = await client.rpc('secure_admin_delete_reported_video', {
            p_report_id: reportId,
            p_action_note: note,
          })
          if (deleteError) throw deleteError
        },
      )
      applyBulkSummary('Eliminación masiva video', summary)
      if (summary.failures.length === 0) {
        setVideoSelectedById({})
      } else {
        const failedSet = new Set(summary.failures.map((failure) => failure.id))
        setVideoSelectedById(
          filteredVideoReports.reduce<Record<string, boolean>>((acc, report) => {
            if (failedSet.has(report.id)) acc[report.id] = true
            return acc
          }, {}),
        )
        setError(`${summary.failures.length}/${summary.total} reportes fallaron en eliminación masiva`)
      }
      await loadVideoReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo eliminar videos reportados seleccionados'))
    } finally {
      setVideoBulkDeleting(false)
    }
  }

  async function applyBulkUserStatus() {
    if (!supabase || selectedUserIds.length === 0 || userBulkLoading) return
    setUserBulkLoading(true)
    setBulkSummaryText(null)
    setBulkFailureDetails([])
    setError(null)
    try {
      const { error: updateError } = await supabase
        .from('reports')
        .update({ status: userBulkStatus })
        .in('id', selectedUserIds)
      if (updateError) throw updateError
      setBulkSummaryText(`Estado masivo usuario: ${selectedUserIds.length}/${selectedUserIds.length} OK`)
      setBulkFailureDetails([])
      setUserSelectedById({})
      await loadUserReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo aplicar estado masivo en reportes de usuario'))
    } finally {
      setUserBulkLoading(false)
    }
  }

  async function deleteBulkUserReports() {
    if (!supabase || selectedUserIds.length === 0 || userBulkDeleting) return
    setUserBulkDeleting(true)
    setBulkSummaryText(null)
    setBulkFailureDetails([])
    setError(null)
    try {
      const { error: deleteError } = await supabase.from('reports').delete().in('id', selectedUserIds)
      if (deleteError) throw deleteError
      setBulkSummaryText(`Eliminación masiva usuario: ${selectedUserIds.length}/${selectedUserIds.length} OK`)
      setBulkFailureDetails([])
      setUserSelectedById({})
      await loadUserReports()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo eliminar reportes de usuario seleccionados'))
    } finally {
      setUserBulkDeleting(false)
    }
  }

  async function suspendSelectedVideoOwners() {
    if (!supabase || selectedVideoOwnerIds.length === 0 || videoBulkSuspending) return
    setVideoBulkSuspending(true)
    setBulkSummaryText(null)
    setBulkFailureDetails([])
    setError(null)
    try {
      const durationValue = Math.max(1, Number(videoBulkSuspendDays) || 1)
      const summary = await runBulkInChunks(
        selectedVideoOwnerIds,
        async (ownerId) => {
          await suspendUserAdvanced(ownerId, {
            durationValue,
            durationUnit: 'days',
            permanent: false,
            reason: 'Suspensión masiva desde reportes de video',
          })
        },
      )
      applyBulkSummary('Suspensión masiva creadores', summary)
      if (summary.failures.length === 0) {
        setVideoSelectedById({})
      } else {
        setError(`${summary.failures.length}/${summary.total} creadores fallaron en suspensión masiva`)
      }
      await loadSuspendedProfiles()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo suspender creadores seleccionados'))
    } finally {
      setVideoBulkSuspending(false)
    }
  }

  async function suspendSelectedReportedUsers() {
    if (!supabase || selectedReportedUserIds.length === 0 || userBulkSuspending) return
    setUserBulkSuspending(true)
    setBulkSummaryText(null)
    setBulkFailureDetails([])
    setError(null)
    try {
      const durationValue = Math.max(1, Number(userBulkSuspendDays) || 1)
      const summary = await runBulkInChunks(
        selectedReportedUserIds,
        async (userId) => {
          await suspendUserAdvanced(userId, {
            durationValue,
            durationUnit: 'days',
            permanent: false,
            reason: 'Suspensión masiva desde reportes de usuario',
          })
        },
      )
      applyBulkSummary('Suspensión masiva reportados', summary)
      if (summary.failures.length === 0) {
        setUserSelectedById({})
      } else {
        setError(`${summary.failures.length}/${summary.total} usuarios fallaron en suspensión masiva`)
      }
      await loadSuspendedProfiles()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo suspender usuarios reportados seleccionados'))
    } finally {
      setUserBulkSuspending(false)
    }
  }

  if (!supabase) return <StatusView title="Supabase no configurado" description="Configura .env para usar moderación." />
  if (loading) return <StatusView title="Verificando permisos admin" description="Espera un momento..." />
  if (!isAdmin) return <StatusView title="Acceso restringido" description="Tu cuenta no tiene permisos admin." />

  return (
    <section className="section-stack">
      <header className="section-header">
        <h2>Moderación Admin</h2>
        <p>Panel web unificado de reportes, suspensiones y eventos.</p>
      </header>

      <div className="row-actions">
        <span className="folder-tag">Video: {videoReports.length}</span>
        <span className="folder-tag">Reportes usuario: {userReports.length}</span>
        <span className="folder-tag">Suspendidos: {suspendedProfiles.length}</span>
        <span className="folder-tag">Eventos: {events.length}</span>
      </div>

      {error ? <StatusView title="Error" description={error} /> : null}
      {bulkSummaryText ? (
        <article className="surface-subcard section-stack">
          <strong>{bulkSummaryText}</strong>
          {bulkFailureDetails.length > 0 ? (
            <div className="list-stack">
              {bulkFailureDetails.map((detail) => (
                <small key={detail}>{detail}</small>
              ))}
            </div>
          ) : (
            <small>Operación masiva completada sin fallos.</small>
          )}
        </article>
      ) : null}

      <article className="surface-card section-stack">
        <h3>Suspender usuario</h3>
        <div className="composer">
          <input
            className="text-input"
            placeholder="UUID del usuario"
            value={suspendUserId}
            onChange={(event) => setSuspendUserId(event.target.value)}
          />
          <input
            className="text-input tiny-input"
            type="number"
            min={1}
            value={suspendDays}
            onChange={(event) => setSuspendDays(Math.max(1, Number(event.target.value) || 1))}
            disabled={suspendPermanent}
          />
          <select
            className="text-input tiny-input"
            value={suspendDurationUnit}
            onChange={(event) =>
              setSuspendDurationUnit(event.target.value as 'minutes' | 'hours' | 'days' | 'years')
            }
            disabled={suspendPermanent}
          >
            <option value="minutes">minutos</option>
            <option value="hours">horas</option>
            <option value="days">días</option>
            <option value="years">años</option>
          </select>
          <label className="checkbox-inline">
            <input
              type="checkbox"
              checked={suspendPermanent}
              onChange={(event) => setSuspendPermanent(event.target.checked)}
            />
            Permanente
          </label>
          <input
            className="text-input"
            placeholder="Razón de suspensión"
            value={suspendReason}
            onChange={(event) => setSuspendReason(event.target.value)}
          />
          <button className="ghost-btn" type="button" disabled={suspending} onClick={() => void applySuspension()}>
            {suspending ? 'Procesando...' : 'Suspender'}
          </button>
          <button className="ghost-btn" type="button" disabled={suspending} onClick={() => void removeSuspension()}>
            Quitar suspensión
          </button>
        </div>
      </article>

      <article className="surface-card section-stack">
        <h3>Reportes de video</h3>
        <div className="composer">
          <select
            className="text-input tiny-input"
            value={videoStatusFilter}
            onChange={(event) => setVideoStatusFilter(event.target.value)}
          >
            <option value="all">Todos los estados</option>
            {videoStatuses.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
          <input
            className="text-input"
            placeholder="Buscar por video, creador, reportero..."
            value={videoSearchQuery}
            onChange={(event) => setVideoSearchQuery(event.target.value)}
          />
          <label className="checkbox-inline">
            <input
              type="checkbox"
              checked={filteredVideoReports.length > 0 && selectedVideoIds.length === filteredVideoReports.length}
              onChange={(event) => toggleSelectAllVideoReports(event.target.checked)}
            />
            Seleccionar visibles
          </label>
          <span className="folder-tag">{filteredVideoReports.length} resultados</span>
          <span className="folder-tag">{selectedVideoIds.length} seleccionados</span>
        </div>
        <div className="composer">
          <select
            className="text-input tiny-input"
            value={videoBulkStatus}
            onChange={(event) => setVideoBulkStatus(event.target.value)}
          >
            {videoStatuses.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
          <input
            className="text-input"
            placeholder="Nota para estado masivo (opcional)"
            value={videoBulkNote}
            onChange={(event) => setVideoBulkNote(event.target.value)}
          />
          <button
            className="ghost-btn"
            type="button"
            disabled={videoBulkLoading || selectedVideoIds.length === 0}
            onClick={() => void applyBulkVideoStatus()}
          >
            {videoBulkLoading ? 'Aplicando...' : 'Aplicar estado masivo'}
          </button>
          <button
            className="ghost-btn"
            type="button"
            disabled={videoBulkWarning || selectedVideoIds.length === 0}
            onClick={() => void warnSelectedVideoReports()}
          >
            {videoBulkWarning ? 'Advirtiendo...' : 'Advertir seleccionados'}
          </button>
          <button
            className="ghost-btn"
            type="button"
            disabled={videoBulkDeleting || selectedVideoIds.length === 0}
            onClick={() => void deleteSelectedReportedVideos()}
          >
            {videoBulkDeleting ? 'Eliminando...' : 'Eliminar videos seleccionados'}
          </button>
          <input
            className="text-input tiny-input"
            type="number"
            min={1}
            value={videoBulkSuspendDays}
            onChange={(event) => setVideoBulkSuspendDays(Math.max(1, Number(event.target.value) || 1))}
          />
          <button
            className="ghost-btn"
            type="button"
            disabled={videoBulkSuspending || selectedVideoOwnerIds.length === 0}
            onClick={() => void suspendSelectedVideoOwners()}
          >
            {videoBulkSuspending
              ? 'Suspendiendo...'
              : `Suspender creadores (${selectedVideoOwnerIds.length})`}
          </button>
        </div>
        {filteredVideoReports.length === 0 ? <p>No hay reportes.</p> : null}
        {filteredVideoReports.map((report) => (
          <article key={report.id} className="surface-subcard">
            <div className="row-between">
              <label className="checkbox-inline">
                <input
                  type="checkbox"
                  checked={videoSelectedById[report.id] === true}
                  onChange={(event) =>
                    setVideoSelectedById((current) => ({
                      ...current,
                      [report.id]: event.target.checked,
                    }))
                  }
                />
                <strong>{report.videoTitle || report.videoId || report.id}</strong>
              </label>
              <span className="folder-tag">{report.status}</span>
            </div>
            <p>
              <strong>Reportado por:</strong> {report.reporterName || report.reporterId}
            </p>
            <p>
              <strong>Creador:</strong> {report.videoOwnerName || report.videoOwnerId}
            </p>
            <div className="row-actions">
              {report.videoId ? (
                <Link className="inline-link" to={`/app/reels/${report.videoId}`}>
                  Abrir video
                </Link>
              ) : null}
              {report.videoOwnerId ? (
                <Link className="inline-link" to={`/app/profile/${report.videoOwnerId}`}>
                  Perfil creador
                </Link>
              ) : null}
              {report.reporterId ? (
                <Link className="inline-link" to={`/app/profile/${report.reporterId}`}>
                  Perfil reportero
                </Link>
              ) : null}
            </div>
            {report.reason ? (
              <p>
                <strong>Motivo:</strong> {report.reason}
              </p>
            ) : null}
            <div className="row-actions">
              <span className="folder-tag">{report.warnSent ? 'Advertido' : 'Sin advertencia'}</span>
              <span className="folder-tag">{report.strikeApplied ? 'Strike aplicado' : 'Sin strike'}</span>
              <span className="folder-tag">Creado: {formatDateTime(report.createdAt)}</span>
            </div>
            <div className="composer">
              <select
                className="text-input tiny-input"
                value={videoStatusById[report.id] ?? report.status}
                onChange={(event) =>
                  setVideoStatusById((current) => ({
                    ...current,
                    [report.id]: event.target.value,
                  }))
                }
              >
                {videoStatuses.map((status) => (
                  <option key={status} value={status}>
                    {status}
                  </option>
                ))}
              </select>
              <input
                className="text-input"
                placeholder="Nota de acción"
                value={videoNoteById[report.id] ?? ''}
                onChange={(event) =>
                  setVideoNoteById((current) => ({
                    ...current,
                    [report.id]: event.target.value,
                  }))
                }
              />
              <button
                className="ghost-btn"
                type="button"
                disabled={videoActionLoading[report.id] === true}
                onClick={() => void updateVideoReport(report.id)}
              >
                Guardar estado
              </button>
              <button
                className="ghost-btn"
                type="button"
                disabled={videoActionLoading[report.id] === true}
                onClick={() => void warnVideoOwner(report.id)}
              >
                Advertir
              </button>
              <button
                className="ghost-btn"
                type="button"
                disabled={videoActionLoading[report.id] === true}
                onClick={() => void deleteVideoReportContent(report.id)}
              >
                Eliminar video
              </button>
            </div>
            <div className="composer">
              <input
                className="text-input tiny-input"
                type="number"
                min={1}
                value={videoSuspendDaysById[report.id] ?? suspendDays}
                onChange={(event) =>
                  setVideoSuspendDaysById((current) => ({
                    ...current,
                    [report.id]: Math.max(1, Number(event.target.value) || 1),
                  }))
                }
              />
              <button
                className="ghost-btn"
                type="button"
                disabled={videoActionLoading[report.id] === true || !report.videoOwnerId}
                onClick={() => void suspendFromVideoReport(report.id)}
              >
                Suspender creador
              </button>
            </div>
          </article>
        ))}
      </article>

      {userReportsAvailable ? (
        <article className="surface-card section-stack">
          <h3>Reportes de usuario</h3>
          <div className="composer">
            <select
              className="text-input tiny-input"
              value={userStatusFilter}
              onChange={(event) => setUserStatusFilter(event.target.value)}
            >
              <option value="all">Todos los estados</option>
              {userStatuses.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
            <input
              className="text-input"
              placeholder="Buscar por reportero, reportado o motivo..."
              value={userSearchQuery}
              onChange={(event) => setUserSearchQuery(event.target.value)}
            />
            <label className="checkbox-inline">
              <input
                type="checkbox"
                checked={filteredUserReports.length > 0 && selectedUserIds.length === filteredUserReports.length}
                onChange={(event) => toggleSelectAllUserReports(event.target.checked)}
              />
              Seleccionar visibles
            </label>
            <span className="folder-tag">{filteredUserReports.length} resultados</span>
            <span className="folder-tag">{selectedUserIds.length} seleccionados</span>
          </div>
          <div className="composer">
            <select
              className="text-input tiny-input"
              value={userBulkStatus}
              onChange={(event) => setUserBulkStatus(event.target.value)}
            >
              {userStatuses.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
            <button
              className="ghost-btn"
              type="button"
              disabled={userBulkLoading || selectedUserIds.length === 0}
              onClick={() => void applyBulkUserStatus()}
            >
              {userBulkLoading ? 'Aplicando...' : 'Aplicar estado masivo'}
            </button>
            <button
              className="ghost-btn"
              type="button"
              disabled={userBulkDeleting || selectedUserIds.length === 0}
              onClick={() => void deleteBulkUserReports()}
            >
              {userBulkDeleting ? 'Eliminando...' : 'Eliminar seleccionados'}
            </button>
            <input
              className="text-input tiny-input"
              type="number"
              min={1}
              value={userBulkSuspendDays}
              onChange={(event) => setUserBulkSuspendDays(Math.max(1, Number(event.target.value) || 1))}
            />
            <button
              className="ghost-btn"
              type="button"
              disabled={userBulkSuspending || selectedReportedUserIds.length === 0}
              onClick={() => void suspendSelectedReportedUsers()}
            >
              {userBulkSuspending
                ? 'Suspendiendo...'
                : `Suspender reportados (${selectedReportedUserIds.length})`}
            </button>
          </div>
          {filteredUserReports.length === 0 ? <p>No hay reportes de usuario.</p> : null}
          {filteredUserReports.map((report) => (
            <article key={report.id} className="surface-subcard">
              <div className="row-between">
                <label className="checkbox-inline">
                  <input
                    type="checkbox"
                    checked={userSelectedById[report.id] === true}
                    onChange={(event) =>
                      setUserSelectedById((current) => ({
                        ...current,
                        [report.id]: event.target.checked,
                      }))
                    }
                  />
                  <strong>{report.reason || 'Reporte de usuario'}</strong>
                </label>
                <span className="folder-tag">{report.status}</span>
              </div>
              <p>
                <strong>Reportero:</strong> {report.reporterName || report.reporterId}
              </p>
              <p>
                <strong>Usuario reportado:</strong> {report.reportedUserName || report.reportedUserId}
              </p>
              <div className="row-actions">
                {report.reporterId ? (
                  <Link className="inline-link" to={`/app/profile/${report.reporterId}`}>
                    Perfil reportero
                  </Link>
                ) : null}
                {report.reportedUserId ? (
                  <Link className="inline-link" to={`/app/profile/${report.reportedUserId}`}>
                    Perfil reportado
                  </Link>
                ) : null}
              </div>
              {report.description ? (
                <p>
                  <strong>Descripción:</strong> {report.description}
                </p>
              ) : null}
              {report.evidenceUrl ? (
                <a className="inline-link" href={report.evidenceUrl} target="_blank" rel="noreferrer">
                  Ver evidencia
                </a>
              ) : null}
              <div className="row-actions">
                <span className="folder-tag">Creado: {formatDateTime(report.createdAt)}</span>
              </div>
              <div className="composer">
                <select
                  className="text-input tiny-input"
                  value={userStatusById[report.id] ?? report.status}
                  onChange={(event) =>
                    setUserStatusById((current) => ({
                      ...current,
                      [report.id]: event.target.value,
                    }))
                  }
                >
                  {userStatuses.map((status) => (
                    <option key={status} value={status}>
                      {status}
                    </option>
                  ))}
                </select>
                <button
                  className="ghost-btn"
                  type="button"
                  disabled={userActionLoading[report.id] === true}
                  onClick={() => void updateUserReport(report.id)}
                >
                  Guardar estado
                </button>
                <button
                  className="ghost-btn"
                  type="button"
                  disabled={userActionLoading[report.id] === true}
                  onClick={() => void deleteUserReport(report.id)}
                >
                  Eliminar reporte
                </button>
                <input
                  className="text-input tiny-input"
                  type="number"
                  min={1}
                  value={userSuspendDaysById[report.id] ?? suspendDays}
                  onChange={(event) =>
                    setUserSuspendDaysById((current) => ({
                      ...current,
                      [report.id]: Math.max(1, Number(event.target.value) || 1),
                    }))
                  }
                />
                <button
                  className="ghost-btn"
                  type="button"
                  disabled={userActionLoading[report.id] === true || !report.reportedUserId}
                  onClick={() => void suspendFromUserReport(report.id)}
                >
                  Suspender reportado
                </button>
              </div>
            </article>
          ))}
        </article>
      ) : (
        <StatusView title="Reportes no disponibles" description="No se encontró la tabla reports en este entorno." />
      )}

      {suspensionModuleAvailable ? (
        <article className="surface-card section-stack">
          <h3>Suspensiones activas</h3>
          <div className="composer">
            <input
              className="text-input"
              placeholder="Buscar por nombre, usuario, email o UUID..."
              value={suspendedSearchQuery}
              onChange={(event) => setSuspendedSearchQuery(event.target.value)}
            />
            <span className="folder-tag">{filteredSuspendedProfiles.length} resultados</span>
          </div>
          {filteredSuspendedProfiles.length === 0 ? <p>No hay cuentas suspendidas.</p> : null}
          {filteredSuspendedProfiles.map((profile) => (
            <article key={profile.id} className="surface-subcard">
              <div className="row-between">
                <strong>{profile.displayName || profile.username || profile.email || profile.id}</strong>
                <span className="folder-tag">Suspendido</span>
              </div>
              <p>
                <strong>UUID:</strong> {profile.id}
              </p>
              <p>
                <strong>Hasta:</strong>{' '}
                {profile.suspensionEndDate
                  ? formatDateTime(profile.suspensionEndDate)
                  : profile.suspendedUntil
                    ? formatDateTime(profile.suspendedUntil)
                    : 'Permanente'}
              </p>
              {profile.suspensionReason ? (
                <p>
                  <strong>Razón:</strong> {profile.suspensionReason}
                </p>
              ) : null}
              <div className="row-actions">
                <span className="folder-tag">Conteo: {profile.suspensionCount}</span>
                <Link className="inline-link" to={`/app/profile/${profile.id}`}>
                  Ver perfil
                </Link>
              </div>
              <div className="composer">
                <button
                  className="ghost-btn"
                  type="button"
                  disabled={suspensionLoadingById[profile.id] === true}
                  onClick={() => void removeSuspension(profile.id)}
                >
                  Levantar suspensión
                </button>
                <button className="ghost-btn" type="button" onClick={() => setSuspendUserId(profile.id)}>
                  Cargar UUID
                </button>
              </div>
            </article>
          ))}
        </article>
      ) : (
        <StatusView
          title="Suspensiones no disponibles"
          description="No se encontraron columnas de suspensión en profiles."
        />
      )}

      {eventsAvailable ? (
        <article className="surface-card section-stack">
          <h3>Eventos de moderación</h3>
          <div className="composer">
            <select
              className="text-input tiny-input"
              value={eventVerdictFilter}
              onChange={(event) => setEventVerdictFilter(event.target.value)}
            >
              <option value="all">Todos</option>
              <option value="allow">allow</option>
              <option value="sanitize">sanitize</option>
              <option value="block">block</option>
            </select>
            <input
              className="text-input"
              placeholder="Buscar por contexto, actor o metadata..."
              value={eventSearchQuery}
              onChange={(event) => setEventSearchQuery(event.target.value)}
            />
            <span className="folder-tag">{filteredEvents.length} resultados</span>
          </div>
          {filteredEvents.length === 0 ? <p>No hay eventos de moderación.</p> : null}
          {filteredEvents.map((event) => (
            <article key={event.id} className="surface-subcard">
              <div className="row-between">
                <strong>{event.context || 'evento'}</strong>
                <span className="folder-tag">{event.verdict}</span>
              </div>
              {event.actorId ? (
                <p>
                  <strong>Actor:</strong> {event.actorId}
                </p>
              ) : null}
              {event.contentRef ? (
                <p>
                  <strong>Referencia:</strong> {event.contentRef}
                </p>
              ) : null}
              <div className="row-actions">
                <span className="folder-tag">Fecha: {formatDateTime(event.createdAt)}</span>
              </div>
              {event.metadataPreview ? (
                <p className="muted-line">
                  <strong>Metadata:</strong> {event.metadataPreview}
                </p>
              ) : null}
            </article>
          ))}
        </article>
      ) : (
        <StatusView title="Eventos no disponibles" description="No se encontró la tabla moderation_events." />
      )}
    </section>
  )
}
