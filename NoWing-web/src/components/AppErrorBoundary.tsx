import { Component, type ErrorInfo, type ReactNode } from 'react'
import { isChunkLoadError, recoverChunkLoad } from '../core/chunkRecovery'
import { interruptedProblem, problemForError, unexpectedProblem } from '../features/errors/errorCatalog'
import { ErrorBrand, ErrorHomeLink, PersonifiedErrorPanel } from '../features/errors/PersonifiedErrorPanel'

type Props = { children: ReactNode; standalone?: boolean }

type Failure = {
  problem: ReturnType<typeof problemForError>
  moduleError: boolean
}

type State = { failure: Failure | null }

export class AppErrorBoundary extends Component<Props, State> {
  state: State = { failure: null }

  static getDerivedStateFromError(error: unknown): State {
    return { failure: { problem: problemForError(error), moduleError: isChunkLoadError(error) } }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('AppErrorBoundary', { name: error instanceof Error ? error.name : 'UnknownError', component: errorInfo.componentStack?.split('\n')[1]?.trim() })
    recoverChunkLoad(error)
  }

  private retry = () => this.setState({ failure: null })

  render() {
    const failure = this.state.failure
    if (!failure) return this.props.children
    const problem = failure.moduleError
      ? interruptedProblem
      : failure.problem ?? unexpectedProblem
    const panel = <PersonifiedErrorPanel problem={problem} actions={<>
      <button className="novvin-error-button solid" type="button"
        onClick={failure.moduleError ? () => location.reload() : this.retry}>
        {failure.moduleError ? 'Recargar aplicación' : 'Reintentar'}
      </button>
      <ErrorHomeLink />
    </>} />
    if (!this.props.standalone) return panel
    return <main className="novvin-error-page" lang="es">
      <header className="novvin-error-header"><ErrorBrand /></header>
      <div className="novvin-error-content">{panel}</div>
    </main>
  }
}
