import { act, fireEvent, render, screen } from '@testing-library/react'
import { MyReportsPage } from '../../../features/reports/MyReportsPage'
import { getStorageReadIdentity, invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../../core/media/storageReadSession'

let mockUser: { id: string } | null = { id: 'account-a' }
let mockFallback = true
const mockLoad = jest.fn()
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: mockFallback }) }))
jest.mock('../../../core/fallbackData', () => ({ loadFallbackProfileMe: (...args: unknown[]) => mockLoad(...args) }))
jest.mock('react-router-dom', () => ({ Link: ({ children }: { children: React.ReactNode }) => children }))

function result(owner: string) {
  return { reports: [{ id: owner, reason: `Private report ${owner}`, status: 'pending' }], stats: { reportsSent: 1 } }
}
function deferred() {
  let resolve!: (value: ReturnType<typeof result>) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<ReturnType<typeof result>>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
beforeEach(() => {
  mockUser = { id: 'account-a' }
  mockFallback = true
  invalidateStorageReadIdentity()
  updateStorageReadIdentity('account-a', 'fallback')
  mockLoad.mockReset()
})

test('clears loaded private reports and loads the new account on a direct switch', async () => {
  mockLoad.mockResolvedValueOnce(result('A')).mockResolvedValueOnce(result('B'))
  const view = render(<MyReportsPage />)
  await screen.findByText('Private report A')
  mockUser = { id: 'account-b' }
  act(() => updateStorageReadIdentity('account-b', 'fallback'))
  view.rerender(<MyReportsPage />)
  expect(screen.queryByText('Private report A')).not.toBeInTheDocument()
  expect(await screen.findByText('Private report B')).toBeInTheDocument()
})

test('ignores an old response delivered after the new account response', async () => {
  const old = deferred()
  mockLoad.mockReturnValueOnce(old.promise).mockResolvedValueOnce(result('B'))
  const view = render(<MyReportsPage />)
  mockUser = { id: 'account-b' }
  act(() => updateStorageReadIdentity('account-b', 'fallback'))
  view.rerender(<MyReportsPage />)
  await screen.findByText('Private report B')
  await act(async () => old.resolve(result('A')))
  expect(screen.queryByText('Private report A')).not.toBeInTheDocument()
  expect(screen.getByText('Private report B')).toBeInTheDocument()
})

test('clears reports on synchronous session invalidation before auth rerenders', async () => {
  mockLoad.mockResolvedValue(result('A'))
  render(<MyReportsPage />)
  await screen.findByText('Private report A')
  act(() => invalidateStorageReadIdentity())
  expect(screen.queryByText('Private report A')).not.toBeInTheDocument()
})

test('does not restore a previous session response after returning to the same account', async () => {
  const old = deferred()
  mockLoad.mockReturnValueOnce(old.promise).mockResolvedValueOnce(result('new A'))
  render(<MyReportsPage />)
  act(() => { invalidateStorageReadIdentity(); updateStorageReadIdentity('account-a', 'fallback') })
  await screen.findByText('Private report new A')
  await act(async () => old.resolve(result('old A')))
  expect(screen.queryByText('Private report old A')).not.toBeInTheDocument()
  expect(screen.getByText('Private report new A')).toBeInTheDocument()
})

test('ignores an old rejection while the current request is loading', async () => {
  const old = deferred(), current = deferred()
  mockLoad.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
  const view = render(<MyReportsPage />)
  mockUser = { id: 'account-b' }
  act(() => updateStorageReadIdentity('account-b', 'fallback'))
  view.rerender(<MyReportsPage />)
  await act(async () => old.reject(new Error('old failure')))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('Cargando reportes')
  await act(async () => current.resolve(result('B')))
  expect(screen.getByText('Private report B')).toBeInTheDocument()
})

test('a current failure can be retried successfully', async () => {
  mockLoad.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(result('A'))
  render(<MyReportsPage />)
  await screen.findByRole('alert')
  fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
  expect(await screen.findByText('Private report A')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(mockLoad).toHaveBeenLastCalledWith(getStorageReadIdentity())
})
