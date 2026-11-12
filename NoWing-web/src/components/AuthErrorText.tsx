import { untrustedWebSessionMessage } from '../core/auth/authErrorMessage'

/** A fixed destination: never forward email, reset codes, tokens, or return paths. */
export function AuthErrorText({ message }: { message: string }) {
  return <>{message}{message === untrustedWebSessionMessage ? <>{' '}<a href="https://novvinapp.com/login" rel="noreferrer">Abrir la web oficial</a></> : null}</>
}
