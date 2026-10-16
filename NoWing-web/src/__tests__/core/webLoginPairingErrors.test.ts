import { authenticationConnectionMessage } from '../../core/auth/authRequest'
import { toPairingError } from '../../core/auth/webLoginPairing'

jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true, fallbackApiJson: jest.fn() }))

test('QR errors do not display raw request URLs or database diagnostics', () => {
  const fallback = 'No se pudo generar el código. Vuelve a intentarlo.'
  for (const raw of ['https://private.example.test/v1/auth/web-login-pairing', 'relation private_accounts does not exist', 'PAIRING_INTERNAL_ERROR']) {
    expect(toPairingError(new Error(raw), fallback)).toBe(fallback)
  }
})

test('a clear Novvin connection error stays understandable', () => {
  expect(toPairingError(new Error(authenticationConnectionMessage), 'Otro error')).toBe(authenticationConnectionMessage)
})
