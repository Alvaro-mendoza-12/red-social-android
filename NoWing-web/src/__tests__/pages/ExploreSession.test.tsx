import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { ExplorePage } from '../../pages/ExplorePage'
import { loadGuestContent, stopGuestBrowsing } from '../../core/auth/guestAccess'
import { randomUUID } from 'node:crypto'
import { AppErrorBoundary } from '../../components/AppErrorBoundary'
import { GuestBrowsingSession } from '../../core/auth/GuestBrowsingSession'
import { publicSharedContentPath } from '../../core/auth/loginReturnPath'

jest.mock('../../core/auth/guestAccess', () => ({ loadGuestContent: jest.fn(async () => []), stopGuestBrowsing: jest.fn() }))
jest.mock('../../core/supabase', () => ({ resolveSupabasePublicUrl: (_bucket: string, url: string) => url }))

function ExploreRoutes() {
  const { pathname } = useLocation()
  return <><GuestBrowsingSession /><AppErrorBoundary key={pathname}><Routes>
    <Route path="/explore/:kind?/:id?" element={<ExplorePage />} />
    <Route path="/login" element={<h1>Cuenta</h1>} />
  </Routes></AppErrorBoundary></>
}

test('guest tab changes keep the media session until the user leaves Explore', async () => {
  jest.clearAllMocks()
  Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: randomUUID })
  const view = render(<MemoryRouter initialEntries={['/explore/posts']}><ExploreRoutes /></MemoryRouter>)
  await screen.findByRole('heading', { name: 'Social', level: 1 })
  await waitFor(() => expect(loadGuestContent).toHaveBeenCalledWith('posts', undefined, expect.any(AbortSignal)))
  fireEvent.click(screen.getByRole('link', { name: 'Videos' }))
  await screen.findByRole('heading', { name: 'Videos', level: 1 })
  await waitFor(() => expect(loadGuestContent).toHaveBeenCalledWith('videos', undefined, expect.any(AbortSignal)))
  expect(stopGuestBrowsing).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('link', { name: 'Social' }))
  await screen.findByRole('heading', { name: 'Social', level: 1 })
  expect(stopGuestBrowsing).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('link', { name: 'Iniciar sesión' }))
  await screen.findByRole('heading', { name: 'Cuenta' })
  expect(stopGuestBrowsing).toHaveBeenCalledTimes(1)
  view.unmount()
  expect(stopGuestBrowsing).toHaveBeenCalledTimes(1)
})

test('an unavailable shared post keeps its return link without reporting a connection error', async () => {
  Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: randomUUID })
  const id = randomUUID()
  const post = `/app/feed/post/${id}`
  jest.mocked(loadGuestContent).mockRejectedValueOnce(new Error('GUEST_READ_404'))
  render(<MemoryRouter initialEntries={[publicSharedContentPath(post)!]}><ExploreRoutes /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: 'Contenido no disponible' })).toBeInTheDocument()
  expect(loadGuestContent).toHaveBeenCalledWith('posts', id, expect.any(AbortSignal))
  expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', `/login?next=${encodeURIComponent(post)}`)
  expect(screen.queryByText('No pudimos cargar el contenido')).not.toBeInTheDocument()
})
