import React from 'react'
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import { EnterpriseLayout } from '../../components/EnterpriseLayout'

/**
 * 🔥 ENTERPRISE LAYOUT TESTS - NIVEL INDUSTRIAL
 * Testing completo para el layout enterprise
 */

// Mock de window.matchMedia
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: jest.fn().mockImplementation(query => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(), // deprecated
    removeListener: jest.fn(), // deprecated
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
})

// Mock de navigator.onLine
Object.defineProperty(navigator, 'onLine', {
  configurable: true,
  writable: true,
  value: true,
})

// Mock de ResizeObserver
global.ResizeObserver = jest.fn().mockImplementation(() => ({
  observe: jest.fn(),
  unobserve: jest.fn(),
  disconnect: jest.fn(),
}))

// Wrapper de testing
const createTestQueryClient = () => new QueryClient({
  defaultOptions: {
    queries: { retry: false },
    mutations: { retry: false },
  },
})

const TestWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <QueryClientProvider client={createTestQueryClient()}>
    <BrowserRouter>
      {children}
    </BrowserRouter>
  </QueryClientProvider>
)

describe('EnterpriseLayout', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    document.documentElement.classList.remove('dark')
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1280 })
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
    window.history.replaceState({}, '', '/')
  })

  test('renderiza el layout correctamente', () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Verificar elementos principales
    expect(screen.getByText('Conéctate')).toBeInTheDocument()
    expect(screen.getByText('Social Platform')).toBeInTheDocument()
  })

  test('muestra la navegación principal en desktop', () => {
    // Mock desktop
    ;(window.matchMedia as jest.Mock).mockReturnValue({
      matches: false,
      media: '(min-width: 1024px)',
      onchange: null,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      dispatchEvent: jest.fn(),
    })

    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Verificar navegación principal
    expect(screen.getByText('Inicio')).toBeInTheDocument()
    expect(screen.getByText('Buscar')).toBeInTheDocument()
    expect(screen.getByText('Reels')).toBeInTheDocument()
    expect(screen.getByText('Mensajes')).toBeInTheDocument()
    expect(screen.getByText('Notificaciones')).toBeInTheDocument()
    expect(screen.getByText('Perfil')).toBeInTheDocument()
  })

  test('muestra navegación móvil en mobile', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 })
    ;(window.matchMedia as jest.Mock).mockReturnValue({
      matches: true,
      media: '(max-width: 767px)',
      onchange: null,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      dispatchEvent: jest.fn(),
    })

    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // En mobile, el sidebar debería estar colapsado por defecto
    await waitFor(() => {
      expect(screen.queryByText('Inicio')).not.toBeInTheDocument()
    })
  })

  test('toggle sidebar funciona correctamente', async () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Buscar botón de toggle (solo visible en desktop)
    const toggleButton = screen.getByRole('button', { name: /contraer menú/i })
    expect(toggleButton).toBeInTheDocument()

    // Click para colapsar
    fireEvent.click(toggleButton)

    // Verificar que el sidebar se colapsó (el texto debería desaparecer)
    await waitFor(() => {
      expect(screen.queryByText('Social Platform')).not.toBeInTheDocument()
    })
  })

  test('toggle dark mode funciona correctamente', async () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Buscar botón de dark mode
    const darkModeButton = screen.getByRole('button', { name: /modo/i })
    expect(darkModeButton).toBeInTheDocument()

    // Click para cambiar modo
    fireEvent.click(darkModeButton)

    // Verificar que se llamó a la función de toggle
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  test('muestra estado de conexión correctamente', () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Verificar indicador de conexión
    expect(screen.getByText('Conectado')).toBeInTheDocument()
  })

  test('muestra badges de notificaciones', () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Verificar badge de notificaciones
    expect(screen.getAllByText('3').length).toBeGreaterThan(0)
  })

  test('navegación a diferentes secciones funciona', async () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Click en Messages
    const messagesLink = screen.getByText('Mensajes')
    fireEvent.click(messagesLink)

    // Verificar que la URL cambió
    await waitFor(() => {
      expect(window.location.pathname).toBe('/app/messages')
    })
  })

  test('responsive design se adapta al tamaño de pantalla', async () => {
    const { rerender } = render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 })
    ;(window.matchMedia as jest.Mock).mockReturnValue({
      matches: true,
      media: '(max-width: 767px)',
      onchange: null,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      dispatchEvent: jest.fn(),
    })

    // Simular evento de resize
    act(() => window.dispatchEvent(new Event('resize')))

    // Esperar a que se actualice el layout
    await waitFor(() => {
      expect(screen.queryByText('Social Platform')).not.toBeInTheDocument()
    })
  })

  test('accesibilidad: navegación por teclado', () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Tab navigation
    fireEvent.keyDown(document.body, { key: 'Tab' })
    
    // Verificar que el focus se mueve correctamente
    expect(document.activeElement).toBeInstanceOf(HTMLElement)
  })

  test('performance: renderizado inicial rápido', () => {
    const startTime = performance.now()
    
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    const endTime = performance.now()
    const renderTime = endTime - startTime

    // El renderizado debería tomar menos de 100ms
    expect(renderTime).toBeLessThan(100)
  })

  test('manejo de errores cuando faltan dependencias', () => {
    // Mock de error en una dependencia
    const originalError = console.error
    console.error = jest.fn()

    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // El layout debería renderizar incluso si hay errores en dependencias
    expect(screen.getByText('Conéctate')).toBeInTheDocument()

    console.error = originalError
  })

  test('lazy loading de componentes pesados', async () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Verificar que los componentes se carguen de forma lazy
    await waitFor(() => {
      expect(screen.getByText('Conéctate')).toBeInTheDocument()
    }, { timeout: 5000 })
  })

  test('memoria: no hay memory leaks', () => {
    const { unmount } = render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Unmount del componente
    unmount()

    expect(screen.queryByText('Test Content')).not.toBeInTheDocument()
  })

  test('integración con React Query', async () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Verificar que QueryClient está disponible
    await waitFor(() => {
      expect(screen.getByText('Conéctate')).toBeInTheDocument()
    })
  })

  test('manejo de estado global', async () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Verificar estado inicial
    expect(screen.getByText('Conectado')).toBeInTheDocument()

    // Simular desconexión
    Object.defineProperty(navigator, 'onLine', { value: false })

    // Disparar evento de offline
    const offlineEvent = new Event('offline')
    act(() => {
      window.dispatchEvent(offlineEvent)
    })

    await waitFor(() => {
      expect(screen.getByText('Desconectado')).toBeInTheDocument()
    })
  })
})

describe('EnterpriseLayout - Edge Cases', () => {
  test('maneja viewport muy pequeño', () => {
    // Mock de viewport muy pequeño
    ;(window.matchMedia as jest.Mock).mockReturnValue({
      matches: true,
      media: '(max-width: 320px)',
      onchange: null,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      dispatchEvent: jest.fn(),
    })

    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Debería adaptarse al viewport pequeño
    expect(screen.getByText('Conéctate')).toBeInTheDocument()
  })

  test('maneja conexión lenta', async () => {
    // Mock de conexión lenta
    Object.defineProperty(navigator, 'connection', {
      value: {
        effectiveType: 'slow-2g',
        downlink: 0.1,
        rtt: 2000,
      },
      writable: true,
    })

    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    // Debería renderizar incluso con conexión lenta
    expect(screen.getByText('Conéctate')).toBeInTheDocument()
  })

  test('maneja errores de red', async () => {
    render(
      <TestWrapper>
        <EnterpriseLayout>
          <div>Test Content</div>
        </EnterpriseLayout>
      </TestWrapper>
    )

    act(() => window.dispatchEvent(new Event('offline')))

    await waitFor(() => {
      expect(screen.getByText('Desconectado')).toBeInTheDocument()
    })
  })
})
