import React, { Component, type ErrorInfo, type ReactNode } from 'react'
import { friendlyError } from '../core/utils'
import { StatusView } from './StatusView'

interface ErrorBoundaryState {
  hasError: boolean
  error: Error | null
  errorInfo: ErrorInfo | null
}

interface ErrorBoundaryProps {
  children: ReactNode
  fallback?: React.ComponentType<{ error: Error; errorInfo: ErrorInfo; onRetry: () => void }>
  onError?: (error: Error, errorInfo: ErrorInfo) => void
}

const FALLBACK_DESCRIPTION =
  'La vista actual tuvo un problema y Conectate no pudo completarla en este momento.'

function ErrorDetails({ error, errorInfo }: { error: Error; errorInfo: ErrorInfo }) {
  if (!import.meta.env.DEV) return null

  return (
    <details className="status-card" style={{ whiteSpace: 'pre-wrap' }}>
      <summary>Detalles técnicos</summary>
      <pre style={{ marginTop: '0.85rem', overflowX: 'auto' }}>
        {error.stack}
        {'\n\n'}
        Component Stack:
        {errorInfo.componentStack}
      </pre>
    </details>
  )
}

const DefaultErrorFallback: React.FC<{
  error: Error
  errorInfo: ErrorInfo
  onRetry: () => void
}> = ({ error, errorInfo, onRetry }) => {
  const description = friendlyError(error, FALLBACK_DESCRIPTION)

  return (
    <section className="section-stack">
      <StatusView
        title="Conectate tuvo una interrupción"
        description={description}
        action={
          <button className="ghost-btn" type="button" onClick={onRetry}>
            Reintentar
          </button>
        }
      />
      <ErrorDetails error={error} errorInfo={errorInfo} />
    </section>
  )
}

class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props)
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null,
    }
  }

  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return {
      hasError: true,
      error,
    }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    this.setState({
      error,
      errorInfo,
    })

    this.props.onError?.(error, errorInfo)
    console.error('Error caught by ErrorBoundary:', error, errorInfo)
  }

  handleRetry = () => {
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null,
    })
  }

  render() {
    if (this.state.hasError && this.state.error && this.state.errorInfo) {
      const FallbackComponent = this.props.fallback || DefaultErrorFallback
      return (
        <FallbackComponent
          error={this.state.error}
          errorInfo={this.state.errorInfo}
          onRetry={this.handleRetry}
        />
      )
    }

    return this.props.children
  }
}

interface SafeComponentProps {
  children: ReactNode
  fallback?: ReactNode
  onError?: (error: Error) => void
}

export const SafeComponent: React.FC<SafeComponentProps> = ({
  children,
  fallback = (
    <DefaultErrorFallback
      error={new Error(FALLBACK_DESCRIPTION)}
      errorInfo={{ componentStack: '' }}
      onRetry={() => {}}
    />
  ),
  onError,
}) => {
  return (
    <ErrorBoundary fallback={() => fallback} onError={onError}>
      {children}
    </ErrorBoundary>
  )
}

interface LoadingWithErrorHandlingProps {
  isLoading: boolean
  error: Error | null
  onRetry: () => void
  children: ReactNode
  fallback?: ReactNode
}

export const LoadingWithErrorHandling: React.FC<LoadingWithErrorHandlingProps> = ({
  isLoading,
  error,
  onRetry,
  children,
  fallback,
}) => {
  if (isLoading) {
    return (
      <div className="status-shell">
        <article className="status-card">
          <h2>Cargando</h2>
          <p>Conectate está preparando esta vista.</p>
        </article>
      </div>
    )
  }

  if (error) {
    return (
      <ErrorBoundary
        fallback={({ error, onRetry: retry }) => (
          <DefaultErrorFallback error={error} errorInfo={{ componentStack: '' }} onRetry={retry} />
        )}
      >
        {fallback || <DefaultErrorFallback error={error} errorInfo={{ componentStack: '' }} onRetry={onRetry} />}
      </ErrorBoundary>
    )
  }

  return <>{children}</>
}

interface AsyncErrorBoundaryState {
  isLoading: boolean
  error: Error | null
}

export class AsyncErrorBoundary extends Component<
  { children: ReactNode; onError?: (error: Error) => void },
  AsyncErrorBoundaryState
> {
  constructor(props: { children: ReactNode; onError?: (error: Error) => void }) {
    super(props)
    this.state = {
      isLoading: false,
      error: null,
    }
  }

  static getDerivedStateFromError(error: Error): AsyncErrorBoundaryState {
    return {
      isLoading: false,
      error,
    }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    this.props.onError?.(error)
    console.error('AsyncErrorBoundary caught an error:', error, errorInfo)
  }

  setLoading = (loading: boolean) => {
    this.setState({ isLoading: loading })
  }

  render() {
    if (this.state.isLoading) {
      return (
        <div className="status-shell">
          <article className="status-card">
            <h2>Cargando</h2>
            <p>Conectate está preparando esta vista.</p>
          </article>
        </div>
      )
    }

    if (this.state.error) {
      return (
        <DefaultErrorFallback
          error={this.state.error}
          errorInfo={{ componentStack: '' }}
          onRetry={() => this.setState({ error: null, isLoading: false })}
        />
      )
    }

    return this.props.children
  }
}

export default ErrorBoundary
