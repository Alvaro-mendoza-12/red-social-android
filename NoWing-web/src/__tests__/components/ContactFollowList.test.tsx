import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ContactFollowList } from '../../components/ContactFollowList'
import { loadFallbackFollows } from '../../core/fallbackData'
jest.mock('../../core/fallbackData', () => ({ loadFallbackFollows: jest.fn() }))
jest.mock('../../core/ubuntuRealtime', () => ({ subscribeUbuntuRealtime: () => jest.fn() }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: ({ url, name }: { url: string; name: string }) => <img src={url} alt={name}/> }))
const load = jest.mocked(loadFallbackFollows)
function view(profileId = 'one') { return <MemoryRouter><ContactFollowList profileId={profileId} tab="followers" accountId="owner"/></MemoryRouter> }
beforeEach(() => load.mockReset())
test('changing a profile hides the old list and cannot accept its late response', async () => {
  let resolve!: (value: { items: Record<string, unknown>[] }) => void
  load.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  load.mockResolvedValueOnce({ items: [{ id: 'new', displayName: 'Nuevo perfil', avatar_url: 'https://example.invalid/new.png' }] })
  const page = render(view()); page.rerender(view('two'))
  await screen.findByText('Nuevo perfil')
  await act(async () => resolve({ items: [{ id: 'old', displayName: 'Perfil anterior' }] }))
  expect(screen.queryByText('Perfil anterior')).not.toBeInTheDocument()
  expect(load.mock.calls[0][2]?.aborted).toBe(true)
})
test('a hidden or failed list offers retry and does not show stale people', async () => {
  load.mockRejectedValueOnce(new Error('private'))
  load.mockResolvedValueOnce({ items: [] }); render(view())
  fireEvent.click(await screen.findByRole('button', { name: 'Reintentar' }))
  expect(await screen.findByText('No hay perfiles visibles en esta lista.')).toBeInTheDocument()
})
test('uses the avatar and displayName provided by Ubuntu and links to the correct person', async () => {
  load.mockResolvedValueOnce({ items: [{ id: 'local', supabase_user_id: 'canonical', displayName: 'Persona visible', avatarUrl: 'https://example.invalid/avatar.png' }] })
  render(view()); const photo = await screen.findByRole('img', { name: 'Persona visible' })
  expect(photo).toHaveAttribute('src', 'https://example.invalid/avatar.png')
  expect(screen.getByRole('link')).toHaveAttribute('href', '/app/profile/canonical')
})
