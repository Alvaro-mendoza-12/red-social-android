import { problemForStatus, interruptedProblem } from '../../features/errors/errorCatalog'
import { ErrorBrand, ErrorHomeLink, PersonifiedErrorPanel } from '../../features/errors/PersonifiedErrorPanel'

export function NotFoundPage() {
  return <main className="novvin-error-page" lang="es">
    <header className="novvin-error-header"><ErrorBrand /><a className="novvin-error-button" href="/login">Iniciar sesión</a></header>
    <div className="novvin-error-content">
      <PersonifiedErrorPanel problem={problemForStatus(404) ?? interruptedProblem} heading="h1" actions={<>
        <ErrorHomeLink />
        <a className="novvin-error-button" href="/app/messages">Abrir mensajes</a>
        <a className="novvin-error-button" href="/ayuda/errores">Errores con personalidad</a>
      </>} />
    </div>
  </main>
}
