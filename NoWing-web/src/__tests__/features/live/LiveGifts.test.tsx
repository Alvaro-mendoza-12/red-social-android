import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { LiveSessionPage } from '../../../features/live/LiveSessionPage'
import { fallbackApiJson } from '../../../core/auth/fallbackAuth'

const fakeSocket = {
  id: 'sock-viewer',
  connected: false,
  on: jest.fn(),
  off: jest.fn(),
  emit: jest.fn(),
  disconnect: jest.fn(),
}

jest.mock('socket.io-client', () => ({
  io: jest.fn(() => fakeSocket),
}))

jest.mock('../../../core/auth/AuthProvider', () => ({
  useAuth: () => ({
    user: {
      id: 'viewer-1',
      email: 'viewer@novvinapp.com',
      user_metadata: { display_name: 'Carlos', local_fallback_id: 'viewer-1' },
    },
    isFallbackSession: true,
  }),
}))

jest.mock('../../../core/auth/fallbackAuth', () => ({
  fallbackApiJson: jest.fn(),
  ensureFallbackAccessToken: jest.fn(async () => 'tok-viewer'),
  isFallbackAuthConfigured: true,
}))

jest.mock('../../../core/fallbackData', () => {
  const actual = jest.requireActual('../../../core/fallbackData')
  return {
    ...actual,
    loadFallbackLiveStream: jest.fn(async () => ({
      stream: {
        id: 'live-1',
        host_user_id: 'creator-1',
        host_name: 'Ana',
        title: 'Directo Nocturno',
        is_live: true,
        status: 'live',
        source: 'camera',
        gifts_enabled: true,
        viewer_count: 12,
        total_reactions: 4,
      },
    })),
    loadFallbackLiveComments: jest.fn(async () => ({ items: [] })),
    reactToFallbackLiveStream: jest.fn(async () => ({ total_reactions: 5 })),
    endFallbackLiveStream: jest.fn(async () => ({})),
  }
})

jest.mock('../../../core/ubuntuRealtime', () => ({
  subscribeUbuntuRealtime: jest.fn(() => () => {}),
}))

jest.mock('../../../features/live/useLiveSfu', () => ({
  useLiveSfu: () => ({
    connected: false,
    error: '',
    hostStream: null,
    hostVideoEnabled: undefined,
    guests: [],
    retry: jest.fn(),
  }),
}))

beforeEach(() => {
  jest.mocked(fallbackApiJson).mockReset()
})

test('viewer sends a virtual gift in Ubuntu Live with stable idempotency retry key', async () => {
  const sentKeys: string[] = []
  let failFirstSend = true

  jest.mocked(fallbackApiJson).mockImplementation(async (path, init) => {
    const cleanPath = String(path)
    if (cleanPath === '/rest/v1/rpc/wallet_get_or_create') {
      return {
        id: 'wallet-1',
        user_id: 'viewer-1',
        balance_diamonds: 120,
        balance_debt_diamonds: 0,
        balance_credits: 120,
        total_spent: 0,
      } as never
    }
    if (cleanPath.startsWith('/rest/v1/virtual_gifts')) {
      return [
        { id: 'gift-rose', name: 'Rosa Novvin', price_diamonds: 15, sort_order: 1 },
      ] as never
    }
    if (cleanPath === '/rest/v1/rpc/send_virtual_gift') {
      const body = JSON.parse(String(init?.body || '{}')) as { p_idempotency_key?: string }
      if (body.p_idempotency_key) sentKeys.push(body.p_idempotency_key)
      if (failFirstSend) {
        failFirstSend = false
        throw new Error('Tiempo de espera agotado')
      }
      return {
        transaction_id: 'tx-gift-1',
        earning_id: 'earn-1',
        sender_balance_diamonds: 105,
        gift_id: 'gift-rose',
        gift_name: 'Rosa Novvin',
        idempotent_replay: false,
      } as never
    }
    return [] as never
  })

  render(
    <MemoryRouter initialEntries={['/live/live-1']}>
      <Routes>
        <Route path="/live/:liveId" element={<LiveSessionPage />} />
      </Routes>
    </MemoryRouter>,
  )

  const openGiftButton = await screen.findByRole('button', { name: 'Enviar regalo' })
  fireEvent.click(openGiftButton)

  expect(await screen.findByText('Rosa Novvin')).toBeInTheDocument()
  expect(screen.getByText(/120 monedas/i)).toBeInTheDocument()

  const sendButton = screen.getByRole('button', { name: 'Enviar' })
  fireEvent.click(sendButton)
  expect(await screen.findByRole('alert')).toHaveTextContent(/Tiempo de espera agotado/i)

  fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))
  await waitFor(() => {
    expect(screen.getByRole('status')).toHaveTextContent(/Enviaste Rosa Novvin\. Saldo restante: 105 monedas\./i)
  })

  expect(sentKeys).toHaveLength(2)
  expect(sentKeys[0]).toBeTruthy()
  expect(sentKeys[1]).toBe(sentKeys[0])
})
