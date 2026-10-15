import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MomentsViewerPage } from '../../features/moments/MomentsViewerPage'
import { fetchMomentsFeed, recordMomentView } from '../../features/moments/momentsData'
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'viewer' } }) }))
jest.mock('../../features/messages/directChat', () => ({ getOrCreateDirectChat: jest.fn(), sendDirectMessage: jest.fn() }))
jest.mock('../../features/moments/momentsData', () => ({ fetchMomentsFeed: jest.fn(), recordMomentView: jest.fn() }))
jest.mock('../../features/moments/MomentPlayback', () => ({ MomentPlayback: ({ moment, paused }: { moment: { id: string }; paused: boolean }) => <div data-testid="playback">{moment.id}:{paused ? 'paused' : 'playing'}</div> }))
const fetchFeed = fetchMomentsFeed as jest.Mock, record = recordMomentView as jest.Mock
beforeEach(() => {
  fetchFeed.mockReset(); fetchFeed.mockResolvedValue(['one', 'two'].map(id => ({ id, userId: 'owner', ownerName: 'Persona', mediaUrl: 'https://media.test/image.png', mediaType: 'image', caption: '', allowReplies: true, viewedByMe: true })))
  record.mockReset(); record.mockResolvedValue(undefined)
})
function mount() { return render(<MemoryRouter initialEntries={['/app/moments/view/one']}><Routes><Route path="/app/moments/view/:momentId" element={<MomentsViewerPage />} /><Route path="/app/moments" element={<p>Lista de momentos</p>} /></Routes></MemoryRouter>) }
test('moving between moments never reloads the whole feed', async () => {
  mount(); await screen.findByText('one:playing')
  fireEvent.click(screen.getByRole('button', { name: 'Ir a historia 2' }))
  await screen.findByText('two:playing'); expect(fetchFeed).toHaveBeenCalledTimes(1)
})
test('reply focus pauses playback and view receipt failures remain nonfatal', async () => {
  fetchFeed.mockResolvedValueOnce([{ id: 'one', userId: 'owner', ownerName: 'Persona', mediaType: 'image', allowReplies: true, viewedByMe: false }]); record.mockRejectedValue(new Error('Offline'))
  mount(); await screen.findByText('one:playing'); await waitFor(() => expect(record).toHaveBeenCalledTimes(1))
  fireEvent.focus(screen.getByPlaceholderText('Responder historia...')); expect(screen.getByText('one:paused')).toBeInTheDocument()
  fireEvent.blur(screen.getByPlaceholderText('Responder historia...')); expect(screen.getByText('one:playing')).toBeInTheDocument()
})
