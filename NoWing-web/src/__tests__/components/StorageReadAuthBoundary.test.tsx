import { useEffect } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { Session } from '@supabase/supabase-js'
import { AuthProvider, useAuth } from '../../core/auth/AuthProvider'
import { getStorageReadIdentity, invalidateStorageReadIdentity } from '../../core/media/storageReadSession'

let mockSession: { user: { id: string }; access_token: string } | null = null
let mockFallback: { accessToken: string; user: { id: string; email: string } } | null = null
let mockAuthEvent: (event: string, session: Session | null) => void
let mockFallbackEvent: () => void
const mockGetSession = jest.fn()
const mockSignOut = jest.fn(async () => {})
const mockSignOutFallback = jest.fn(async () => {})

jest.mock('../../core/supabase', () => ({
  isSupabaseConfigured: true,
  supabase: {
    auth: {
      getSession: (...args: unknown[]) => mockGetSession(...args),
      onAuthStateChange: (callback: typeof mockAuthEvent) => { mockAuthEvent = callback; return { data: { subscription: { unsubscribe: jest.fn() } } } },
      signOut: () => mockSignOut(),
    },
    rpc: async () => ({ data: null, error: null }),
  },
}))
jest.mock('../../core/auth/sessionBootstrap', () => ({ bootstrapSessionProfile: async () => {} }))
jest.mock('../../core/auth/fallbackAuth', () => ({
  isFallbackAuthConfigured: true,
  readFallbackSession: () => mockFallback,
  hasFallbackSessionHint: () => false,
  fallbackSessionToUser: (session: typeof mockFallback) => session?.user ?? null,
  fallbackSessionNeedsRefresh: () => false,
  upgradeFallbackWebSession: async () => mockFallback,
  restoreFallbackSession: async () => mockFallback,
  clearFallbackSession: () => { mockFallback = null; mockFallbackEvent?.() },
  signOutFallbackSession: () => mockSignOutFallback(),
  onFallbackAuthChanged: (callback: () => void) => { mockFallbackEvent = callback; return () => {} },
}))

function Consumer() {
  const auth = useAuth()
  return <><span data-testid="user">{auth.user?.id ?? 'none'}</span><button onClick={() => void auth.signOut()}>Salir</button></>
}
function session(id: string, token = 'synthetic.test.signature') { return { user: { id }, access_token: token } as Session }
beforeEach(() => {
  mockSession = null; mockFallback = null; mockGetSession.mockReset(); mockSignOut.mockClear(); mockSignOutFallback.mockReset(); mockSignOutFallback.mockResolvedValue(undefined)
  mockGetSession.mockImplementation(async () => ({ data: { session: mockSession } })); invalidateStorageReadIdentity()
})
afterEach(() => act(() => invalidateStorageReadIdentity()))

test('binds initial Supabase session before protected media can read', async () => {
  mockSession = session('account-a'); render(<AuthProvider><Consumer /></AuthProvider>)
  await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('account-a'))
  expect(getStorageReadIdentity()).toMatchObject({ accountId: 'account-a', provider: 'supabase' })
})

test('Supabase token renewal keeps generation but account switching changes it', async () => {
  mockSession = session('account-a'); render(<AuthProvider><Consumer /></AuthProvider>)
  await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('account-a'))
  const identity = getStorageReadIdentity()
  await act(async () => mockAuthEvent('TOKEN_REFRESHED', session('account-a', 'renewed.test.signature')))
  expect(getStorageReadIdentity()).toBe(identity)
  await act(async () => mockAuthEvent('SIGNED_IN', session('account-b')))
  expect(getStorageReadIdentity()).not.toBe(identity); expect(getStorageReadIdentity().accountId).toBe('account-b')
})

test('late bootstrap result cannot resurrect A after B auth event', async () => {
  let complete!: (value: { data: { session: Session | null } }) => void
  mockGetSession.mockImplementation(() => new Promise((resolve) => { complete = resolve }))
  render(<AuthProvider><Consumer /></AuthProvider>)
  await act(async () => mockAuthEvent('SIGNED_IN', session('account-b')))
  await act(async () => complete({ data: { session: session('account-a') } }))
  expect(screen.getByTestId('user')).toHaveTextContent('account-b'); expect(getStorageReadIdentity().accountId).toBe('account-b')
})

test('SIGNED_OUT invalidates a previously authorized read', async () => {
  mockSession = session('account-a'); render(<AuthProvider><Consumer /></AuthProvider>)
  await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('account-a'))
  const identity = getStorageReadIdentity(); await act(async () => mockAuthEvent('SIGNED_OUT', null))
  expect(getStorageReadIdentity().accountId).toBe(''); expect(getStorageReadIdentity()).not.toBe(identity)
})

test('fallback logout invalidates before its network promise completes', async () => {
  mockFallback = { accessToken: 'synthetic.test.signature', user: { id: 'fallback-a', email: 'synthetic@invalid' } }
  let complete!: () => void; mockSignOutFallback.mockImplementation(() => new Promise((resolve) => { complete = resolve }))
  render(<AuthProvider><Consumer /></AuthProvider>)
  expect(getStorageReadIdentity().accountId).toBe('fallback-a')
  fireEvent.click(screen.getByRole('button', { name: 'Salir' }))
  expect(getStorageReadIdentity().accountId).toBe(''); expect(screen.getByTestId('user')).toHaveTextContent('fallback-a')
  await act(async () => complete()); expect(screen.getByTestId('user')).toHaveTextContent('none')
})

test('finishing A logout does not clear new fallback session B', async () => {
  mockFallback = { accessToken: 'synthetic.test.signature', user: { id: 'fallback-a', email: 'synthetic@invalid' } }
  let complete!: () => void; mockSignOutFallback.mockImplementation(() => new Promise((resolve) => { complete = resolve }))
  render(<AuthProvider><Consumer /></AuthProvider>)
  fireEvent.click(screen.getByRole('button', { name: 'Salir' }))
  mockFallback = { accessToken: 'synthetic.test.signature', user: { id: 'fallback-b', email: 'synthetic@invalid' } }
  act(() => mockFallbackEvent())
  await act(async () => complete())
  expect(screen.getByTestId('user')).toHaveTextContent('fallback-b'); expect(getStorageReadIdentity().accountId).toBe('fallback-b')
})

function UserEffectProbe({ onChange }: { onChange: () => void }) {
  const { user } = useAuth()
  useEffect(onChange, [user, onChange])
  return <span>{user?.email}</span>
}

test('fallback token renewal preserves unchanged user effects on open screens', async () => {
  mockFallback = { accessToken: 'synthetic.first.signature', user: { id: 'fallback-a', email: 'synthetic@invalid' } }
  const changed = jest.fn()
  render(<AuthProvider><UserEffectProbe onChange={changed} /></AuthProvider>)
  await waitFor(() => expect(changed).toHaveBeenCalledTimes(1))
  mockFallback = { accessToken: 'synthetic.renewed.signature', user: { id: 'fallback-a', email: 'synthetic@invalid' } }
  await act(async () => mockFallbackEvent())
  expect(changed).toHaveBeenCalledTimes(1)
  mockFallback = { ...mockFallback, user: { ...mockFallback.user, email: 'changed@invalid' } }
  await act(async () => mockFallbackEvent())
  expect(changed).toHaveBeenCalledTimes(2)
})

test('Supabase token renewal does not restart unchanged user effects', async () => {
  mockSession = session('account-a')
  const changed = jest.fn()
  render(<AuthProvider><UserEffectProbe onChange={changed} /></AuthProvider>)
  await waitFor(() => expect(changed).toHaveBeenCalledTimes(2))
  await act(async () => mockAuthEvent('TOKEN_REFRESHED', session('account-a', 'synthetic.renewed.signature')))
  expect(changed).toHaveBeenCalledTimes(2)
  await act(async () => mockAuthEvent('SIGNED_IN', session('account-b')))
  expect(changed).toHaveBeenCalledTimes(3)
})
