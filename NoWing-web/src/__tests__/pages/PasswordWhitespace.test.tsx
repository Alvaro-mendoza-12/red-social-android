import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { PasswordResetPage } from '../../pages/auth/PasswordResetPage'
import { LoginPage } from '../../pages/auth/LoginPage'
import { completeMigratedPasswordReset, confirmFallbackPasswordReset } from '../../core/auth/fallbackAuth'
import { signInWithResolvedEmail } from '../../core/auth/sessionBootstrap'

jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../core/auth/fallbackAuth', () => ({
  isFallbackAuthConfigured: true,
  completeMigratedPasswordReset: jest.fn(), confirmFallbackPasswordReset: jest.fn(),
  requestFallbackPasswordReset: async () => ({ resetToken: 'test-reset-token' }),
  FallbackTwoFactorRequiredError: class extends Error {},
  FallbackPasswordChangedNeedsTwoFactorError: class extends Error {},
  isMigratedAccountPasswordSetupRequired: () => true,
  isMigratedPasswordResetRequired: () => false,
}))
jest.mock('../../core/auth/sessionBootstrap', () => ({ signInWithResolvedEmail: jest.fn() }))
jest.mock('../../core/auth/webLoginPairing', () => ({}))
jest.mock('../../core/webPreferences', () => ({ useWebPreferences: () => [{}] }))
jest.mock('../../components/QrCodeTile', () => ({ QrCodeTile: () => null }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: () => null }))
const exact = ' clave multibyte ñ 🐈 '
beforeEach(() => {
  jest.clearAllMocks()
  ;(completeMigratedPasswordReset as jest.Mock).mockRejectedValue(new Error('Synthetic stop after payload'))
  ;(confirmFallbackPasswordReset as jest.Mock).mockRejectedValue(new Error('Synthetic stop after payload'))
})

test.each([true, false])('legacy reset preserves password whitespace (migration=%s)', async migrated => {
  render(<PasswordResetPage email="test@example.invalid" migrationToken={migrated ? 'test-migration' : undefined} />)
  fireEvent.click(screen.getByRole('button', { name: /Continuar/ }))
  fireEvent.change(await screen.findByLabelText('Nueva Contraseña'), { target: { value: exact } })
  fireEvent.change(screen.getByLabelText('Confirmar Contraseña'), { target: { value: exact } })
  fireEvent.click(screen.getByRole('button', { name: /Guardar Contraseña/ }))
  await waitFor(() => expect(migrated ? completeMigratedPasswordReset : confirmFallbackPasswordReset).toHaveBeenCalledWith(migrated ? 'test-migration' : 'test-reset-token', exact))
})

test('login migration keeps the exact password payload', async () => {
  ;(signInWithResolvedEmail as jest.Mock).mockRejectedValue({ email: 'test@example.invalid', migrationToken: 'test-migration', displayName: 'Test' })
  render(<MemoryRouter initialEntries={['/login']}><LoginPage /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('Correo'), { target: { value: 'test@example.invalid' } })
  fireEvent.change(screen.getByLabelText('Contraseña'), { target: { value: 'old-password' } })
  fireEvent.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
  fireEvent.change(await screen.findByLabelText('Nueva contraseña local'), { target: { value: exact } })
  fireEvent.change(screen.getByLabelText('Confirmar contraseña local'), { target: { value: exact } })
  fireEvent.click(screen.getByRole('button', { name: 'Crear contraseña y entrar' }))
  await waitFor(() => expect(completeMigratedPasswordReset).toHaveBeenCalledWith('test-migration', exact))
})
