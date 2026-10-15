import { Component, type ErrorInfo, type ReactNode } from 'react'
import { StatusView } from './StatusView'

type Props = {
  children: ReactNode
}

type State = {
  hasError: boolean
  message: string
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
    }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('AppErrorBoundary', { error, errorInfo })
  }

  private retry = () => {
    this.setState({ hasError: false, message: '' })
  }

  render() {
    if (this.state.hasError) {
      return (
        <section className="section-stack">
          <StatusView
            title="Se recuperó un fallo de interfaz"
            description={this.state.message}
          />
          <div className="status-action">
            <button className="ghost-btn" type="button" onClick={this.retry}>
              Reintentar
            </button>
          </div>
        </section>
      )
    }
    return this.props.children
  }
}
