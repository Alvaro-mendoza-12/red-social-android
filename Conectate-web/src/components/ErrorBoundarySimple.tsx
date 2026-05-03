import React, { Component, type ErrorInfo, type ReactNode } from 'react'

/**
 * 🔥 ERROR BOUNDARY SIMPLE - PREVENCIÓN DE CRASHES EN WEB
 * Componente para manejar errores y prevenir crashes en la UI
 * Basado en best practices oficiales de React
 */

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

class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props)
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null
    }
  }

  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return {
      hasError: true,
      error
    }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    this.setState({
      error,
      errorInfo
    })

    // 🔥 BEST PRACTICE: Reportar errores a servicios de monitoreo
    this.props.onError?.(error, errorInfo)
    
    // 🔥 BEST PRACTICE: Loguear errores para debugging
    console.error('Error caught by ErrorBoundary:', error, errorInfo)
  }

  handleRetry = () => {
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null
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

/**
 * 🔥 DEFAULT ERROR FALLBACK - UI AMIGABLE PARA ERRORES
 */
const DefaultErrorFallback: React.FC<{
  error: Error
  errorInfo: ErrorInfo
  onRetry: () => void
}> = ({ error, errorInfo, onRetry }) => {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100vh',
        padding: '16px',
        fontFamily: 'system-ui, -apple-system, sans-serif'
      }}
    >
      <div
        style={{
          maxWidth: '600px',
          width: '100%',
          backgroundColor: 'white',
          borderRadius: '12px',
          boxShadow: '0 4px 6px rgba(0, 0, 0, 0.1)',
          padding: '32px',
          textAlign: 'center'
        }}
      >
        {/* Icono de error */}
        <div style={{ fontSize: '4rem', marginBottom: '16px' }}>
          ⚠️
        </div>
        
        {/* Título de error */}
        <h2
          style={{
            fontSize: '1.5rem',
            fontWeight: 'bold',
            marginBottom: '8px',
            color: '#333'
          }}
        >
          Oops! Algo salió mal
        </h2>
        
        {/* Mensaje de error */}
        <p
          style={{
            fontSize: '1rem',
            color: '#dc2626',
            marginBottom: '24px'
          }}
        >
          {error.message || 'Error desconocido'}
        </p>
        
        {/* Botón de retry */}
        <button
          onClick={onRetry}
          style={{
            backgroundColor: '#3b82f6',
            color: 'white',
            border: 'none',
            padding: '12px 24px',
            borderRadius: '8px',
            fontSize: '1rem',
            cursor: 'pointer',
            marginBottom: '24px',
            display: 'flex',
            alignItems: 'center',
            gap: '8px'
          }}
        >
          🔄 Reintentar
        </button>
        
        {/* Detalles del error (solo en desarrollo) */}
        {process.env.NODE_ENV === 'development' && (
          <div
            style={{
              backgroundColor: '#fef2f2',
              border: '1px solid #fecaca',
              borderRadius: '8px',
              padding: '16px',
              marginTop: '16px'
            }}
          >
            <h3
              style={{
                fontSize: '1rem',
                fontWeight: 'bold',
                marginBottom: '8px',
                color: '#333'
              }}
            >
              Detalles del error:
            </h3>
            <pre
              style={{
                textAlign: 'left',
                fontSize: '0.75rem',
                overflow: 'auto',
                maxHeight: '200px',
                backgroundColor: 'white',
                padding: '8px',
                borderRadius: '4px',
                border: '1px solid #e5e7eb'
              }}
            >
              {error.stack}
              {'\n\n'}
              Component Stack:
              {errorInfo.componentStack}
            </pre>
          </div>
        )}
      </div>
    </div>
  )
}

/**
 * 🔥 SAFE COMPONENT WRAPPER - WRAPPER SEGURO
 */
interface SafeComponentProps {
  children: ReactNode
  fallback?: ReactNode
  onError?: (error: Error) => void
}

export const SafeComponent: React.FC<SafeComponentProps> = ({ 
  children, 
  fallback = <DefaultErrorFallback error={new Error('Component error')} errorInfo={{ componentStack: '' }} onRetry={() => {}} />, 
  onError 
}) => {
  return (
    <ErrorBoundary fallback={() => fallback} onError={onError}>
      {children}
    </ErrorBoundary>
  )
}

/**
 * 🔥 LOADING WITH ERROR HANDLING - MANEJO DE CARGA Y ERRORES
 */
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
  fallback
}) => {
  if (isLoading) {
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '100vh'
        }}
      >
        <div
          style={{
            width: '60px',
            height: '60px',
            border: '4px solid #f3f4f6',
            borderTop: '4px solid #3b82f6',
            borderRadius: '50%',
            animation: 'spin 1s linear infinite'
          }}
        />
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

export default ErrorBoundary
