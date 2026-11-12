import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { LoginPage } from '../../pages/auth/LoginPage'
import { signInWithResolvedEmail } from '../../core/auth/sessionBootstrap'

jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../core/auth/fallbackAuth', () => ({
  isFallbackAuthConfigured: true,
  FallbackTwoFactorRequiredError: class extends Error {},
  isMigratedAccountPasswordSetupRequired: () => false,
  isMigratedPasswordResetRequired: () => false,
}))
jest.mock('../../core/auth/sessionBootstrap', () => ({ signInWithResolvedEmail: jest.fn() }))
jest.mock('../../core/auth/webLoginPairing', () => ({}))
jest.mock('../../core/webPreferences', () => ({ useWebPreferences: () => [{}] }))
jest.mock('../../components/QrCodeTile', () => ({ QrCodeTile: () => null }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: () => null }))

async function submit(error: Error) {
  jest.mocked(signInWithResolvedEmail).mockRejectedValue(error)
  render(<MemoryRouter initialEntries={['/login?email=qa@example.invalid&next=/app/messages']}><LoginPage /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('Contraseña'), { target: { value: 'synthetic password' } })
  fireEvent.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
  return screen.findByRole('alert')
}

beforeEach(() => jest.clearAllMocks())

test('an untrusted web session explains the rejected address and links only to official login', async () => {
  const alert = await submit(new Error('UNTRUSTED_WEB_SESSION_REQUEST'))
  expect(alert).toHaveTextContent('Esta dirección web no está autorizada')
  expect(alert).not.toHaveTextContent('UNTRUSTED_WEB_SESSION_REQUEST')
  const link = screen.getByRole('link', { name: 'Abrir la web oficial' })
  expect(link).toHaveAttribute('href', 'https://novvinapp.com/login')
  expect(link).toHaveAttribute('rel', 'noreferrer')
  expect(screen.queryByText('Actualiza tu contraseña')).not.toBeInTheDocument()
  expect(signInWithResolvedEmail).toHaveBeenCalledTimes(1)
})

test('other forbidden errors remain generic and are not diagnosed as a domain problem', async () => {
  const alert = await submit(Object.assign(new Error('ACCOUNT_SUSPENDED'), { status: 403 }))
  expect(alert).toHaveTextContent('No se pudo iniciar sesión')
  expect(screen.queryByRole('link', { name: 'Abrir la web oficial' })).not.toBeInTheDocument()
})

test('the official-site hint disappears with the error when changing login mode', async () => {
  await submit(new Error('UNTRUSTED_WEB_SESSION_REQUEST'))
  expect(screen.getByRole('link', { name: 'Abrir la web oficial' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Contraseña', exact: true }))
  await waitFor(() => expect(screen.queryByRole('link', { name: 'Abrir la web oficial' })).not.toBeInTheDocument())
})
