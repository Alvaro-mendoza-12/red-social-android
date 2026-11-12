import { apiErrorCode, friendlyError } from '../utils'

export const untrustedWebSessionMessage = 'Esta dirección web no está autorizada para iniciar sesión o recuperar la cuenta. Abre la web oficial y vuelve a intentarlo. Este error no indica que debas cambiar tu contraseña.'

export function isUntrustedWebSessionError(error: unknown): boolean {
  return apiErrorCode(error) === 'UNTRUSTED_WEB_SESSION_REQUEST'
}

export function authErrorMessage(error: unknown, fallback: string): string {
  return isUntrustedWebSessionError(error) ? untrustedWebSessionMessage : friendlyError(error, fallback)
}
