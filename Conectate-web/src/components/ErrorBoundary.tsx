import React, { Component, type ErrorInfo, type ReactNode } from 'react'
import { Heart, RefreshCw, AlertTriangle } from 'lucide-react'

/**
 * 🔥 ERROR BOUNDARY - PREVENCIÓN DE CRASHES EN WEB
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
    <Box
      display="flex"
      flexDirection="column"
      alignItems="center"
      justifyContent="center"
      minHeight="100vh"
      p={3}
    >
      <Card sx={{ maxWidth: 600, width: '100%' }}>
        <CardContent sx={{ textAlign: 'center', p: 4 }}>
          {/* Icono de error */}
          <Typography variant="h1" sx={{ fontSize: '4rem', mb: 2 }}>
            ⚠️
          </Typography>
          
          {/* Título de error */}
          <Typography variant="h4" gutterBottom fontWeight="bold">
            Oops! Algo salió mal
          </Typography>
          
          {/* Mensaje de error */}
          <Typography variant="body1" color="error" sx={{ mb: 3 }}>
            {error.message || 'Error desconocido'}
          </Typography>
          
          {/* Botón de retry */}
          <Button
            variant="contained"
            startIcon={<Refresh />}
            onClick={onRetry}
            sx={{ mb: 3 }}
          >
            Reintentar
          </Button>
          
          {/* Detalles del error (solo en desarrollo) */}
          {process.env.NODE_ENV === 'development' && (
            <Card sx={{ mt: 2, bgcolor: 'error.light' }}>
              <CardContent sx={{ p: 2 }}>
                <Typography variant="h6" gutterBottom fontWeight="bold">
                  Detalles del error:
                </Typography>
                <Typography variant="body2" component="pre" sx={{ 
                  textAlign: 'left',
                  fontSize: '0.75rem',
                  overflow: 'auto',
                  maxHeight: '200px',
                  bgcolor: 'background.paper',
                  p: 1,
                  borderRadius: 1
                }}>
                  {error.stack}
                  {'\n\n'}
                  Component Stack:
                  {errorInfo.componentStack}
                </Typography>
              </CardContent>
            </Card>
          )}
        </CardContent>
      </Card>
    </Box>
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
      <Box
        display="flex"
        alignItems="center"
        justifyContent="center"
        minHeight="100vh"
      >
        <CircularProgress size={60} />
      </Box>
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

/**
 * 🔥 ASYNC ERROR BOUNDARY - PARA OPERACIONES ASÍNCRONAS
 */
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
      error: null
    }
  }

  static getDerivedStateFromError(error: Error): AsyncErrorBoundaryState {
    return {
      isLoading: false,
      error
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
        <Box
          display="flex"
          alignItems="center"
          justifyContent="center"
          minHeight="100vh"
        >
          <CircularProgress size={60} />
        </Box>
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
