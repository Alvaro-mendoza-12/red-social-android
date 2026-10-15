jest.mock('../../core/auth/fallbackAuth', () => ({
  requestFallbackPasswordReset: jest.fn(), verifyFallbackPasswordReset: jest.fn(), confirmFallbackPasswordReset: jest.fn(),
  FallbackPasswordChangedNeedsTwoFactorError: class extends Error {},
}))
jest.mock('react-router-dom', () => ({
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
  useSearchParams: () => [new URLSearchParams()], useNavigate: () => mockNavigate,
}))
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ForgotPasswordPage } from '../../pages/auth/ForgotPasswordPage'
import { requestFallbackPasswordReset, verifyFallbackPasswordReset, confirmFallbackPasswordReset } from '../../core/auth/fallbackAuth'
const mockNavigate = jest.fn()
beforeEach(() => { jest.clearAllMocks(); sessionStorage.clear() })
test('email, six digits and password are separate; passwords preserve spaces', async () => {
  jest.mocked(requestFallbackPasswordReset).mockResolvedValue({ ok: true, codeLength: 6, delivery: 'email' })
  jest.mocked(verifyFallbackPasswordReset).mockResolvedValue({ resetToken: 'fixture-grant' })
  jest.mocked(confirmFallbackPasswordReset).mockResolvedValue({} as never)
  render(<ForgotPasswordPage />)
  fireEvent.change(screen.getByLabelText('Correo de tu cuenta'), { target: { value: 'qa@example.invalid' } })
  fireEvent.click(screen.getByRole('button', { name: 'Enviar código' }))
  await screen.findByLabelText('Código de seis dígitos')
  expect(screen.queryByLabelText('Nueva contraseña')).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Código de seis dígitos'), { target: { value: '001234' } })
  fireEvent.click(screen.getByRole('button', { name: 'Verificar código' }))
  await screen.findByLabelText('Nueva contraseña')
  expect(verifyFallbackPasswordReset).toHaveBeenCalledWith('qa@example.invalid', '001234')
  expect(sessionStorage.getItem('novvin:email-recovery-draft:v1')).not.toContain('fixture-grant')
  fireEvent.change(screen.getByLabelText('Nueva contraseña'), { target: { value: ' fixture password ' } })
  fireEvent.change(screen.getByLabelText('Repite la nueva contraseña'), { target: { value: ' fixture password ' } })
  fireEvent.click(screen.getByRole('button', { name: 'Guardar contraseña y entrar' }))
  await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/app/messages', { replace: true }))
  expect(confirmFallbackPasswordReset).toHaveBeenCalledWith('fixture-grant', ' fixture password ')
})
test('reopening keeps the email code screen without persisting secrets', () => {
  sessionStorage.setItem('novvin:email-recovery-draft:v1', JSON.stringify({ email: 'qa@example.invalid', requested: true, savedAt: Date.now() }))
  render(<ForgotPasswordPage />)
  expect(screen.getByLabelText('Código de seis dígitos')).toHaveValue('')
  expect(screen.queryByLabelText('Nueva contraseña')).not.toBeInTheDocument()
})
