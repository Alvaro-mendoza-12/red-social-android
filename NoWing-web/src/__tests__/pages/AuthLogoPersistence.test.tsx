import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { LoginPage } from '../../pages/auth/LoginPage'
import { RegisterPage } from '../../pages/auth/RegisterPage'
import { ForgotPasswordPage } from '../../pages/auth/ForgotPasswordPage'
import { invalidateStorageReadIdentity } from '../../core/media/storageReadSession'
// Keep the real cleanup listener: mocking StreamingMedia would hide this regression.
import '../../core/media/browserStreaming'

jest.mock('../../core/runtimeBackend', () => ({ rewriteRuntimeStorageUrl: (url: string) => url }))
jest.mock('../../core/media/storageReadClient', () => ({
  trustedStorageBackend: (url: string) => url.startsWith('https://private.test/') ? 'https://private.test' : '',
  prepareStorageReadAccessToken: jest.fn(),
}))
jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../core/auth/fallbackAuth', () => ({
  isFallbackAuthConfigured: true,
  FallbackTwoFactorRequiredError: class extends Error {},
  FallbackPasswordChangedNeedsTwoFactorError: class extends Error {},
  isMigratedAccountPasswordSetupRequired: () => false,
  isMigratedPasswordResetRequired: () => false,
}))
jest.mock('../../core/auth/sessionBootstrap', () => ({ signInWithResolvedEmail: jest.fn(), signUpAndBootstrapProfile: jest.fn() }))
jest.mock('../../core/auth/webLoginPairing', () => ({}))
jest.mock('../../core/webPreferences', () => ({ useWebPreferences: () => [{ accentTheme: 'violet' }] }))
jest.mock('../../components/QrCodeTile', () => ({ QrCodeTile: () => null }))

beforeEach(() => sessionStorage.clear())

test.each([
  ['login', LoginPage],
  ['register', RegisterPage],
  ['forgot-password', ForgotPasswordPage],
] as const)('%s keeps its public logo after account media is cleared', (route, Page) => {
  render(<MemoryRouter initialEntries={[`/${route}`]}><Page />
    <img alt="Synthetic private media" data-novvin-media="" src="/__novvin_media/synthetic-private-handle" />
  </MemoryRouter>)
  const logo = screen.getByRole('img', { name: /^(Logo de )?Novvin$/ })
  expect(logo).toHaveAttribute('src', '/novvin-logo.png')

  act(() => invalidateStorageReadIdentity())

  expect(logo).toHaveAttribute('src', '/novvin-logo.png')
  expect(screen.getByRole('img', { name: 'Synthetic private media' })).not.toHaveAttribute('src')
})
