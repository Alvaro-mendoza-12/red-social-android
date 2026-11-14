import { Component, type ErrorInfo, type ReactNode } from 'react'
import { StatusView } from './StatusView'
import { isChunkLoadError, recoverChunkLoad, reloadApplicationSafely } from '../core/chunkRecovery'

type Props = {
  children: ReactNode
}

type State = {
  hasError: boolean
  message: string
  moduleError?: boolean
  reloadBlocked?: boolean
}

export class AppErrorBoundary extends Component<Props, State> {
  state: State = {
    hasError: false,
    message: '',
  }

  static getDerivedStateFromError(error: unknown): State {
    const message =
      error instanceof Error && error.message.trim().length > 0
        ? error.message.trim()
        : 'Se produjo un fallo inesperado en la interfaz.'
    return {
      hasError: true,
      message,
      moduleError:isChunkLoadError(error),
    }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('AppErrorBoundary', { error, errorInfo })
    recoverChunkLoad(error)
  }

  private retry = () => {
    this.setState({ hasError: false, message: '', reloadBlocked: false })
  }

  render() {
    if (this.state.hasError) {
      return (
        <section className="section-stack">
          <StatusView
            title={this.state.moduleError?'No pudimos abrir esta sección':'No pudimos mostrar esta pantalla'}
            description={this.state.reloadBlocked?'Termina la llamada o transmisión y guarda tus borradores antes de recargar.':this.state.moduleError?'Puede haber una actualización disponible o un problema de conexión. Puedes ir a otra sección o recargar la aplicación.':'Vuelve a intentarlo. Si el problema continúa, regresa a otra sección.'}
          />
          <div className="status-action">
            <button className="ghost-btn" type="button" onClick={this.state.moduleError?()=>this.setState({reloadBlocked:!reloadApplicationSafely()}):this.retry}>
              {this.state.moduleError?'Recargar aplicación':'Reintentar'}
            </button>
          </div>
        </section>
      )
    }
    return this.props.children
  }
}
