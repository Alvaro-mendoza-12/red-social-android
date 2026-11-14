import { act, fireEvent, render, screen } from '@testing-library/react'
import { RouteLoadingStatus } from '../../components/RouteLoadingStatus'
import { reloadApplicationSafely } from '../../core/chunkRecovery'
jest.mock('../../core/chunkRecovery', () => ({ reloadApplicationSafely: jest.fn() }))
const reload = jest.mocked(reloadApplicationSafely)
beforeEach(() => { jest.useFakeTimers(); reload.mockReset() })
afterEach(() => { jest.useRealTimers() })

test('a stalled section offers recovery and does not reload by itself', () => {
  const { unmount } = render(<RouteLoadingStatus />)
  expect(screen.getByText('Cargando sección…')).toBeInTheDocument()
  act(() => { jest.advanceTimersByTime(12_000) })
  expect(screen.getByText('La carga está tardando')).toBeInTheDocument()
  expect(reload).not.toHaveBeenCalled()
  reload.mockReturnValue(false)
  fireEvent.click(screen.getByRole('button', { name: 'Reintentar carga' }))
  expect(screen.getByText(/guarda tus borradores/)).toBeInTheDocument()
  reload.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: 'Reintentar carga' }))
  expect(reload).toHaveBeenCalledTimes(2)
  unmount()
  expect(jest.getTimerCount()).toBe(0)
})

test('reports connectivity changes while an import is pending', () => {
  const online = jest.spyOn(window.navigator, 'onLine', 'get')
  online.mockReturnValue(false)
  try {
    render(<RouteLoadingStatus />)
    expect(screen.getByText('Sin conexión')).toBeInTheDocument()
    online.mockReturnValue(true)
    fireEvent(window, new Event('online'))
    expect(screen.getByText('Cargando sección…')).toBeInTheDocument()
  } finally { online.mockRestore() }
})
