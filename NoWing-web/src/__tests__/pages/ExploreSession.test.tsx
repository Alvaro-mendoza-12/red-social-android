import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { ExplorePage } from '../../pages/ExplorePage'
import { loadGuestContent, stopGuestBrowsing } from '../../core/auth/guestAccess'
import { randomUUID } from 'node:crypto'
import { AppErrorBoundary } from '../../components/AppErrorBoundary'
import { GuestBrowsingSession } from '../../core/auth/GuestBrowsingSession'

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
