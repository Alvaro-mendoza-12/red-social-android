import { useEffect } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useNavigate } from 'react-router-dom'
import { LiveSessionHost } from '../../features/live/LiveSessionHost'
let mockOwner = 'account-a'
const mockMount = jest.fn(), mockDispose = jest.fn()
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockOwner ? { id: mockOwner } : null }) }))
jest.mock('../../features/live/LiveSessionPage', () => ({ LiveSessionPage: ({ liveIdOverride, minimized, onExit }: { liveIdOverride: string; minimized: boolean; onExit: () => void }) => {
  useEffect(() => { mockMount(liveIdOverride); return () => mockDispose(liveIdOverride) }, [liveIdOverride])
  return <div><span>{liveIdOverride}:{minimized ? 'minimized' : 'full'}</span><button onClick={onExit}>Leave session</button></div>
} }))
function Harness() {
  const navigate = useNavigate()
  return <><button onClick={() => navigate('/app/messages')}>Messages</button><button onClick={() => navigate('/app/live/room-a?host=1&source=screen')}>Return</button><LiveSessionHost/></>
}
beforeEach(() => { mockOwner = 'account-a'; mockMount.mockClear(); mockDispose.mockClear() })
test('keeps the same session mounted while navigating away and back', async () => {
  render(<MemoryRouter initialEntries={['/app/live/room-a?host=1&source=screen']}><Harness/></MemoryRouter>)
  await screen.findByText('room-a:full')
  fireEvent.click(screen.getByText('Messages')); await screen.findByText('room-a:minimized')
  fireEvent.click(screen.getByText('Return')); await screen.findByText('room-a:full')
  expect(mockMount).toHaveBeenCalledTimes(1); expect(mockDispose).not.toHaveBeenCalled()
})
test('destroys the old media owner on account switch and sign out', async () => {
  const { rerender } = render(<MemoryRouter initialEntries={['/app/live/room-a?host=1&source=screen']}><Harness/></MemoryRouter>)
  await screen.findByText('room-a:full')
  mockOwner = 'account-b'; rerender(<MemoryRouter><Harness/></MemoryRouter>)
  expect(mockDispose).toHaveBeenCalledTimes(1)
  mockOwner = ''; rerender(<MemoryRouter><Harness/></MemoryRouter>)
  expect(screen.queryByText('room-a:full')).toBeNull()
})
