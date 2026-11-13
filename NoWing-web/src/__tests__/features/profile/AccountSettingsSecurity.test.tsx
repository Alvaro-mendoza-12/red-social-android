import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AccountSettingsPage } from '../../../features/profile/AccountSettingsPage'
import {
  changeFallbackPassword,
  deleteFallbackOwnAccount,
  enableFallbackTwoFactor,
  getFallbackTwoFactorStatus,
  listFallbackWebSessions,
  revokeFallbackWebSession,
  setupFallbackTwoFactor,
} from '../../../core/auth/fallbackAuth'
import { loadFallbackProfileMe } from '../../../core/fallbackData'

const mockNavigate = jest.fn()

jest.mock('react-router-dom', () => {
  const actual = jest.requireActual('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

jest.mock('../../../core/supabase', () => ({ supabase: null }))
jest.mock('../../../core/auth/AuthProvider', () => ({
  useAuth: () => ({
    user: { id: 'user-1', email: 'ana@novvinapp.com', user_metadata: { display_name: 'Ana' } },
    isFallbackSession: true,
  }),
}))
jest.mock('../../../core/fallbackData', () => ({
  loadFallbackProfileMe: jest.fn(),
  updateFallbackProfileMe: jest.fn(),
  uploadFallbackFile: jest.fn(),
}))
jest.mock('../../../core/auth/fallbackAuth', () => ({
  changeFallbackPassword: jest.fn(),
  updateFallbackSessionProfile: jest.fn(),
  getFallbackTwoFactorStatus: jest.fn(),
  setupFallbackTwoFactor: jest.fn(),
  enableFallbackTwoFactor: jest.fn(),
  disableFallbackTwoFactor: jest.fn(),
  listFallbackWebSessions: jest.fn(),
  revokeFallbackWebSession: jest.fn(),
  revokeAllFallbackWebSessions: jest.fn(),
  deleteFallbackOwnAccount: jest.fn(),
}))

beforeEach(() => {
  jest.clearAllMocks()
  jest.mocked(loadFallbackProfileMe).mockResolvedValue({
    profile: { display_name: 'Ana', username: 'ana_novvin' },
  } as never)
  jest.mocked(getFallbackTwoFactorStatus).mockResolvedValue({
    enabled: false,
    recoveryCodesRemaining: 0,
  })
  jest.mocked(setupFallbackTwoFactor).mockResolvedValue({
    secret: 'JBSWY3DPEHPK3PXP',
    provisioningUri: 'otpauth://totp/Novvin:ana@novvinapp.com?secret=JBSWY3DPEHPK3PXP',
  })
  jest.mocked(enableFallbackTwoFactor).mockResolvedValue({
    recoveryCodes: ['REC-1111', 'REC-2222'],
  })
  jest.mocked(listFallbackWebSessions).mockResolvedValue([
    {
      sessionId: 'sess-1',
      deviceLabel: 'Chrome en Windows',
      ipAddress: '198.51.100.12',
      userAgent: 'Mozilla/5.0',
      createdAt: '2026-11-12T10:00:00Z',
      lastSeenAt: '2026-11-12T11:00:00Z',
      expiresAt: '2026-11-19T10:00:00Z',
      revokedAt: null,
    },
  ])
  jest.mocked(revokeFallbackWebSession).mockResolvedValue()
  jest.mocked(deleteFallbackOwnAccount).mockResolvedValue()
})

test('supports 2FA setup/enable, active web session revocation, account deletion and valid access links', async () => {
  const { container } = render(
    <MemoryRouter>
      <AccountSettingsPage />
    </MemoryRouter>,
  )

  expect(await screen.findByText(/Desactivada/i)).toBeInTheDocument()
  expect(screen.getByText('Chrome en Windows')).toBeInTheDocument()

  // Broken ?mode=code and ?mode=recovery links must not exist; valid /forgot-password & ?mode=qr links must exist.
  const links = Array.from(container.querySelectorAll('a'))
  expect(links.some((anchor) => anchor.getAttribute('href')?.includes('mode=code'))).toBe(false)
  expect(links.some((anchor) => anchor.getAttribute('href')?.includes('mode=recovery'))).toBe(false)
  expect(
    links.some((anchor) => anchor.getAttribute('href')?.startsWith('/forgot-password?email=ana%40novvinapp.com')),
  ).toBe(true)
  expect(links.some((anchor) => anchor.getAttribute('href') === '/login?mode=qr')).toBe(true)

  // Setup & enable 2FA
  fireEvent.change(screen.getByLabelText(/Contraseña actual para 2FA/i), {
    target: { value: 'ClaveSegura1!' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Preparar 2FA (TOTP)' }))
  expect(await screen.findByText(/Clave manual: JBSWY3DPEHPK3PXP/i)).toBeInTheDocument()

  fireEvent.change(screen.getByLabelText(/Código 2FA de 6 dígitos/i), {
    target: { value: '654321' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Activar 2FA' }))
  expect(await screen.findByText(/REC-1111 · REC-2222/)).toBeInTheDocument()

  // Revoke single web session
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar sesión' }))
  await waitFor(() => {
    expect(revokeFallbackWebSession).toHaveBeenCalledWith('sess-1', 'user-1')
  })
  expect(screen.queryByText('Chrome en Windows')).not.toBeInTheDocument()

  // Delete own account requires typing ELIMINAR
  fireEvent.click(screen.getByRole('button', { name: 'Eliminar mi cuenta' }))
  fireEvent.change(screen.getByLabelText(/Escribe ELIMINAR para confirmar/i), {
    target: { value: 'ELIMINAR' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar eliminación' }))
  await waitFor(() => {
    expect(deleteFallbackOwnAccount).toHaveBeenCalledWith('user-1')
    expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true })
  })
  expect(changeFallbackPassword).not.toHaveBeenCalled()
}, 15000)
