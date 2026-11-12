import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { PostInvitePage } from '../../../features/social/PostInvitePage'
import { sharedPostAndroidIntent, sharedPostId } from '../../../core/postShareLinks'

test('a publication shared outside Android retains its exact web destination', async () => {
  render(<MemoryRouter initialEntries={['/post/original-123']}><Routes>
    <Route path="/post/:postId" element={<PostInvitePage />} />
    <Route path="/app/feed/post/:postId" element={<p>Publicación original</p>} />
  </Routes></MemoryRouter>)
  expect(await screen.findByText('Publicación original')).toBeInTheDocument()
})

test('Android gets an explicit app action with a fallback to the same original', () => {
  const agent = jest.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue('Android Chrome')
  try {
    render(<MemoryRouter initialEntries={['/post/original-123']}><Routes>
      <Route path="/post/:postId" element={<PostInvitePage />} />
    </Routes></MemoryRouter>)
    expect(screen.getByRole('link', { name: 'Abrir en la app' })).toHaveAttribute('href', sharedPostAndroidIntent('original-123'))
    expect(screen.getByRole('link', { name: 'Continuar en la web' })).toHaveAttribute('href', '/app/feed/post/original-123')
    expect(decodeURIComponent(sharedPostAndroidIntent('original-123'))).toContain('S.browser_fallback_url=https://novvinapp.com/app/feed/post/original-123;end')
  } finally { agent.mockRestore() }
})

test('invalid identifiers cannot inject a route or intent option', () => {
  for (const id of ['a/b', '../other', 'x;scheme=evil', 'a%2Fb', 'x'.repeat(161)]) expect(sharedPostId(id)).toBeNull()
  render(<MemoryRouter initialEntries={['/post/a%2Fb']}><Routes>
    <Route path="/post/:postId" element={<PostInvitePage />} />
  </Routes></MemoryRouter>)
  expect(screen.getByText('Enlace de publicación inválido')).toBeInTheDocument()
})
