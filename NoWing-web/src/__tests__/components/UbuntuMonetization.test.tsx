import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MonetizationCenterPage } from '../../features/profile/MonetizationCenterPage'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'

jest.mock('../../core/auth/AuthProvider', () => ({
  useAuth: () => ({
    user: { id: 'creator-1', user_metadata: { local_fallback_id: 'creator-1' } },
    isFallbackSession: true,
  }),
}))
jest.mock('../../core/auth/fallbackAuth', () => ({
  isFallbackAuthConfigured: true,
  fallbackApiJson: jest.fn(),
}))
jest.mock('../../core/supabase', () => ({
  supabase: null,
  reportSupabaseServiceRestrictionIfPresent: jest.fn(),
}))

beforeEach(() => {
  jest.mocked(fallbackApiJson).mockReset()
  jest.mocked(fallbackApiJson).mockImplementation(async (path, init) => {
    const cleanPath = String(path)
    if (cleanPath === '/rest/v1/rpc/wallet_get_or_create') {
      return { id: 'w-1', user_id: 'creator-1', balance_diamonds: 350, balance_debt_diamonds: 0, balance_credits: 350, total_spent: 50 } as never
    }
    if (cleanPath.startsWith('/rest/v1/virtual_gifts')) {
      return [{ id: 'gift-rose', name: 'Rosa Novvin', price_diamonds: 10, sort_order: 1 }] as never
    }
    if (cleanPath.startsWith('/rest/v1/gift_transactions')) {
      return [{
        id: 'tx-1',
        sender_id: 'fan-1',
        sender_name: 'Lucia',
        receiver_id: 'creator-1',
        gift_id: 'gift-rose',
        gift_name: 'Rosa Novvin',
        amount_diamonds: 10,
        status: 'committed',
        created_at: '2026-11-12T10:00:00Z',
      }] as never
    }
    if (cleanPath.startsWith('/rest/v1/creator_earnings')) {
      return [{
        id: 'earn-1',
        creator_id: 'creator-1',
        source_type: 'virtual_gift',
        amount_diamonds: 10,
        withdrawable_amount_diamonds: 10,
        status: 'pending',
        created_at: '2026-11-12T10:00:00Z',
      }] as never
    }
    if (cleanPath.startsWith('/rest/v1/creator_program_applications') && init?.method === 'POST') {
      return [{ id: 'app-1', status: 'pending', niche: 'Musica', payout_country: 'PE', audience_size: 2000 }] as never
    }
    return [] as never
  })
})

test('loads Ubuntu wallet, gifts, creator earnings and keeps withdrawals disabled as pending', async () => {
  render(<MemoryRouter><MonetizationCenterPage /></MemoryRouter>)
  expect((await screen.findAllByText('350')).length).toBeGreaterThanOrEqual(1)
  expect(screen.getAllByText('Rosa Novvin').length).toBeGreaterThanOrEqual(2)
  expect(screen.getByText(/De Lucia · 10 monedas/)).toBeInTheDocument()
  const withdrawalButton = screen.getByRole('button', { name: 'Retiro pendiente de habilitación' })
  expect(withdrawalButton).toBeDisabled()

  fireEvent.change(screen.getByPlaceholderText(/gaming, música/i), { target: { value: 'Musica' } })
  fireEvent.change(screen.getAllByPlaceholderText('PE')[0], { target: { value: 'PE' } })
  fireEvent.change(screen.getByPlaceholderText('1000'), { target: { value: '2000' } })
  fireEvent.click(screen.getByRole('button', { name: 'Enviar solicitud' }))

  await waitFor(() => {
    expect(fallbackApiJson).toHaveBeenCalledWith(
      '/rest/v1/creator_program_applications',
      expect.objectContaining({ method: 'POST' }),
    )
  })
  expect(await screen.findByText(/Solicitud de creador guardada/)).toBeInTheDocument()
})
