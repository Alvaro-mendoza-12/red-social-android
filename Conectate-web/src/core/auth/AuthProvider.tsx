/* eslint-disable react-refresh/only-export-components */
import type { Session, User } from '@supabase/supabase-js'
import { createContext, useContext, useEffect, useMemo, useState, type PropsWithChildren } from 'react'
import { Navigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { isSupabaseConfigured, supabase } from '../../core/supabase'
import { textOf, type GenericRow } from '../../core/utils'
import {
  clearFallbackSession,
  fallbackSessionToUser,
  isFallbackAuthConfigured,
  onFallbackAuthChanged,
  readFallbackSession,
  type FallbackSession,
} from './fallbackAuth'
import { bootstrapSessionProfile } from './sessionBootstrap'

type SuspensionState = {
  isSuspended: boolean
  suspendedUntil: string
  reason: string
}

type AuthContextValue = {
  configured: boolean
  loading: boolean
  user: User | null
  session: Session | null
  isFallbackSession: boolean
  suspension: SuspensionState | null
  suspensionLoading: boolean
  signOut: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: PropsWithChildren) {
  const [loading, setLoading] = useState(isSupabaseConfigured)
  const initialFallbackSession = readFallbackSession()
  const [session, setSession] = useState<Session | null>(null)
  const [fallbackSession, setFallbackSession] = useState<FallbackSession | null>(initialFallbackSession)
  const [user, setUser] = useState<User | null>(() => fallbackSessionToUser(initialFallbackSession))
  const [suspension, setSuspension] = useState<SuspensionState | null>(null)
  const [suspensionLoading, setSuspensionLoading] = useState(false)

  useEffect(() => {
    if (!supabase) return
    const client = supabase as NonNullable<typeof supabase>

    async function applySession(nextSession: Session | null) {
      setSession(nextSession ?? null)
      if (!nextSession) {
        const storedFallbackSession = readFallbackSession()
        setFallbackSession(storedFallbackSession)
        setUser(fallbackSessionToUser(storedFallbackSession))
        return
      }
      clearFallbackSession()
      setFallbackSession(null)
      setUser(nextSession.user ?? null)
      await bootstrapSessionProfile(client, nextSession)
    }

    void client.auth
      .getSession()
      .then(async ({ data }) => {
        await applySession(data.session ?? null)
      })
      .catch((error) => {
        console.warn('AuthProvider getSession bootstrap failed', error)
      })
      .finally(() => setLoading(false))

    const {
      data: { subscription },
    } = client.auth.onAuthStateChange((_event, nextSession) => {
      void applySession(nextSession ?? null)
        .catch((error) => {
          console.warn('AuthProvider session bootstrap failed', error)
        })
        .finally(() => setLoading(false))
    })

    return () => {
      subscription.unsubscribe()
    }
  }, [])

  useEffect(() => {
    return onFallbackAuthChanged(() => {
      const storedFallbackSession = readFallbackSession()
      setFallbackSession(storedFallbackSession)
      if (!session) {
        setUser(fallbackSessionToUser(storedFallbackSession))
      }
    })
  }, [session])

  useEffect(() => {
    if (!supabase || !user || fallbackSession) {
      setSuspension(null)
      setSuspensionLoading(false)
      return
    }
    const client = supabase as NonNullable<typeof supabase>
    const currentUser = user

    async function loadSuspensionState() {
      setSuspensionLoading(true)
      try {
        const rpcResult = await client.rpc('secure_get_my_suspension_state')
        if (!rpcResult.error) {
          const row = (Array.isArray(rpcResult.data) ? rpcResult.data[0] : rpcResult.data) as
            | GenericRow
            | undefined
          if (!row) {
            setSuspension(null)
          } else {
            const isSuspended = Boolean(row.is_suspended)
            setSuspension(
              isSuspended
                ? {
                    isSuspended: true,
                    suspendedUntil: textOf(row, ['suspension_end_date', 'suspended_until'], ''),
                    reason: textOf(row, ['suspension_reason'], ''),
                  }
                : null,
            )
          }
          return
        }

        const message = String(rpcResult.error.message ?? '').toLowerCase()
        const canFallback =
          message.includes('could not find') ||
          message.includes('function') ||
          message.includes('does not exist') ||
          message.includes('42883')
        if (!canFallback) {
          setSuspension(null)
          return
        }

        const { data: profileRows, error: profileError } = await client
          .from('profiles')
          .select('is_suspended,suspended_until,suspension_end_date,suspension_reason')
          .eq('id', currentUser.id)
          .limit(1)
        if (profileError) {
          setSuspension(null)
          return
        }
        const profile = ((profileRows ?? []) as GenericRow[])[0] ?? {}
        if (!profile.is_suspended) {
          setSuspension(null)
          return
        }
        setSuspension({
          isSuspended: true,
          suspendedUntil: textOf(profile, ['suspension_end_date', 'suspended_until'], ''),
          reason: textOf(profile, ['suspension_reason'], ''),
        })
      } finally {
        setSuspensionLoading(false)
      }
    }

    void loadSuspensionState()
  }, [fallbackSession, user])

  const value = useMemo<AuthContextValue>(
    () => ({
      configured: isSupabaseConfigured || isFallbackAuthConfigured,
      loading,
      session,
      isFallbackSession: Boolean(fallbackSession),
      user,
      suspension,
      suspensionLoading,
      signOut: async () => {
        clearFallbackSession()
        setFallbackSession(null)
        setSession(null)
        setUser(null)
        if (!supabase) return
        await supabase.auth.signOut()
      },
    }),
    [fallbackSession, loading, session, suspension, suspensionLoading, user],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth debe usarse dentro de AuthProvider')
  }
  return context
}

export function RequireAuth({ children }: PropsWithChildren) {
  const { configured, loading, user, suspension, suspensionLoading, signOut } = useAuth()
  if (!configured) {
    return (
      <StatusView
        title="Falta conectar el backend"
        description="Configura VITE_FALLBACK_API_URL en .env para usar tu servidor Ubuntu."
      />
    )
  }
  if (loading) {
    return <StatusView title="Cargando sesión" description="Espera un momento..." />
  }
  if (!user) {
    return <Navigate to="/login" replace />
  }
  if (suspensionLoading) {
    return <StatusView title="Verificando estado de cuenta" description="Espera un momento..." />
  }
  if (suspension?.isSuspended) {
    const untilLabel = suspension.suspendedUntil
      ? new Date(suspension.suspendedUntil).toLocaleString()
      : 'Permanente'
    return (
      <section className="section-stack">
        <StatusView
          title="Cuenta suspendida"
          description={`Tu cuenta está suspendida hasta ${untilLabel}.${suspension.reason ? ` Motivo: ${suspension.reason}.` : ''}`}
        />
        <div className="status-action">
          <button className="ghost-btn" type="button" onClick={() => void signOut()}>
            Cerrar sesión
          </button>
        </div>
      </section>
    )
  }
  return <>{children}</>
}

export function GuestOnlyRoute({ children }: PropsWithChildren) {
  const { loading, user, configured } = useAuth()
  if (!configured) return <>{children}</>
  if (loading) {
    return <StatusView title="Cargando sesión" description="Espera un momento..." />
  }
  if (user) {
    return <Navigate to="/app/messages" replace />
  }
  return <>{children}</>
}
