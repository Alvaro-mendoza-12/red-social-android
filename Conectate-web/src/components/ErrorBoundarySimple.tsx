import React, { Component, type ErrorInfo, type ReactNode } from 'react'
import { friendlyError } from '../core/utils'

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

const shellStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  minHeight: '100vh',
  padding: '16px',
  fontFamily: 'system-ui, -apple-system, sans-serif',
}

const cardStyle: React.CSSProperties = {
  maxWidth: '600px',
  width: '100%',
  backgroundColor: 'white',
  borderRadius: '12px',
  boxShadow: '0 4px 6px rgba(0, 0, 0, 0.1)',
  padding: '32px',
  textAlign: 'center',
}

const DefaultErrorFallback: React.FC<{
  error: Error
  errorInfo: ErrorInfo
  onRetry: () => void
}> = ({ error, errorInfo, onRetry }) => {
  const description = friendlyError(error, FALLBACK_DESCRIPTION)

  return (
    <div style={shellStyle}>
      <div style={cardStyle}>
        <div style={{ fontSize: '4rem', marginBottom: '16px' }}>⚠️</div>
        <h2
          style={{
            fontSize: '1.5rem',
            fontWeight: 'bold',
            marginBottom: '8px',
            color: '#182033',
          }}
        >
          Conectate tuvo una interrupción
        </h2>
        <p
          style={{
            fontSize: '1rem',
            color: '#b42318',
            marginBottom: '24px',
          }}
        >
          {description}
        </p>
        <button
          onClick={onRetry}
          style={{
            backgroundColor: '#ff5d36',
            color: 'white',
            border: 'none',
            padding: '12px 24px',
            borderRadius: '999px',
            fontSize: '1rem',
            cursor: 'pointer',
            marginBottom: '24px',
          }}
        >
          Reintentar
        </button>
        {import.meta.env.DEV && (
          <details
            style={{
              backgroundColor: '#fef2f2',
              border: '1px solid #fecaca',
              borderRadius: '8px',
              padding: '16px',
              marginTop: '16px',
              textAlign: 'left',
            }}
          >
            <summary style={{ fontWeight: 'bold', cursor: 'pointer' }}>Detalles técnicos</summary>
            <pre
              style={{
                marginTop: '12px',
                fontSize: '0.75rem',
                overflow: 'auto',
                maxHeight: '200px',
                backgroundColor: 'white',
                padding: '8px',
                borderRadius: '4px',
                border: '1px solid #e5e7eb',
                whiteSpace: 'pre-wrap',
              }}
            >
              {error.stack}
              {'\n\n'}
              Component Stack:
              {errorInfo.componentStack}
            </pre>
          </details>
        )}
      </div>
    </div>
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
      <div style={shellStyle}>
        <div style={cardStyle}>
          <h2 style={{ marginBottom: '8px' }}>Cargando</h2>
          <p>Conectate está preparando esta vista.</p>
        </div>
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

export { ErrorBoundary as ErrorBoundarySimple }
export default ErrorBoundary
