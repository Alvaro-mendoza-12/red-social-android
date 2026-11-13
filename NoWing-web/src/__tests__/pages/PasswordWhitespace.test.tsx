import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ForgotPasswordPage } from '../../pages/auth/ForgotPasswordPage'
import { RegisterPage } from '../../pages/auth/RegisterPage'
import {
  confirmFallbackPasswordReset,
  fallbackApiJson,
  requestFallbackPasswordReset,
  verifyFallbackPasswordReset,
} from '../../core/auth/fallbackAuth'
import { signUpAndBootstrapProfile } from '../../core/auth/sessionBootstrap'

jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../core/auth/fallbackAuth', () => {
  const actual = jest.requireActual('../../core/auth/fallbackAuth')
  return {
    ...actual,
    isFallbackAuthConfigured: true,
    fallbackApiJson: jest.fn(),
    requestFallbackPasswordReset: jest.fn(),
    verifyFallbackPasswordReset: jest.fn(),
    confirmFallbackPasswordReset: jest.fn(),
  }
})
jest.mock('../../core/auth/sessionBootstrap', () => ({
  signUpAndBootstrapProfile: jest.fn(),
}))

beforeEach(() => {
  jest.useFakeTimers()
  jest.clearAllMocks()
  sessionStorage.clear()
})

afterEach(() => {
  jest.useRealTimers()
})

test('preserves leading and trailing whitespace in ForgotPasswordPage password confirmation', async () => {
  jest.mocked(requestFallbackPasswordReset).mockResolvedValue({
    requested: true,
    delivery: 'email',
  })
  jest.mocked(verifyFallbackPasswordReset).mockResolvedValue({ resetToken: 'reset-tok-1' })
  jest.mocked(confirmFallbackPasswordReset).mockResolvedValue({
    id: 'user-1',
    email: 'test@novvinapp.com',
  } as never)

  render(
    <MemoryRouter initialEntries={['/forgot-password']}>
      <ForgotPasswordPage />
    </MemoryRouter>,
  )

  fireEvent.change(screen.getByLabelText(/Correo de tu cuenta/i), {
    target: { value: 'test@novvinapp.com' },
  })
  fireEvent.submit(screen.getByLabelText(/Correo de tu cuenta/i).closest('form')!)

  const codeInput = await screen.findByLabelText(/Código de seis dígitos/i)
  fireEvent.change(codeInput, { target: { value: '123456' } })
  fireEvent.submit(codeInput.closest('form')!)

  const newPassInput = await screen.findByLabelText(/^Nueva contraseña$/i)
  fireEvent.change(newPassInput, { target: { value: '  secret 123  ' } })
  fireEvent.change(screen.getByLabelText(/Repite la nueva contraseña/i), {
    target: { value: '  secret 123  ' },
  })
  fireEvent.submit(newPassInput.closest('form')!)

  await waitFor(() => {
    expect(confirmFallbackPasswordReset).toHaveBeenCalledWith('reset-tok-1', '  secret 123  ')
  })
})

test('preserves leading and trailing whitespace in RegisterPage password submission', async () => {
  jest.mocked(fallbackApiJson).mockResolvedValue({
    username: 'usuario_demo',
    available: true,
    suggestions: [],
  } as never)
  jest.mocked(signUpAndBootstrapProfile).mockResolvedValue()

  render(
    <MemoryRouter initialEntries={['/register']}>
      <RegisterPage />
    </MemoryRouter>,
  )

  fireEvent.change(screen.getByLabelText('Nombre visible', { exact: true }), { target: { value: 'Usuario Demo' } })
  jest.advanceTimersByTime(350)
  expect(await screen.findByText(/Disponible\. Será tu identificador permanente/i)).toBeInTheDocument()

  fireEvent.change(screen.getByLabelText(/^Correo$/i), { target: { value: 'demo@novvinapp.com' } })
  fireEvent.change(screen.getByLabelText(/^Contraseña$/i), { target: { value: '  secret 123  ' } })
  fireEvent.change(screen.getByLabelText(/Fecha de nacimiento/i), {
    target: { value: '2000-05-15' },
  })
  fireEvent.submit(screen.getByRole('button', { name: 'Crear cuenta' }).closest('form')!)

  await waitFor(() => {
    expect(signUpAndBootstrapProfile).toHaveBeenCalledWith(
      null,
      expect.objectContaining({
        email: 'demo@novvinapp.com',
        password: '  secret 123  ',
        birthDate: '2000-05-15',
      }),
    )
  })
})
