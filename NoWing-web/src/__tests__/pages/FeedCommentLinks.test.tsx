import { randomUUID } from 'node:crypto'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { FeedPage } from '../../pages/feed/FeedPage'
import { loadFallbackFeed } from '../../core/fallbackData'
jest.mock('../../core/auth/AuthProvider', () => {
  const user = { id: 'synthetic-account', user_metadata: {} }
  return { useAuth: () => ({ user, isFallbackSession: true }) }
})
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true }))
jest.mock('../../core/fallbackData', () => ({ loadFallbackFeed: jest.fn() }))
jest.mock('../../core/ubuntuRealtime', () => ({ subscribeUbuntuRealtime: () => () => {} }))
jest.mock('../../core/supabase', () => ({ supabase: null, resolveSupabasePublicUrl: (_bucket: string, url: string) => url }))
jest.mock('../../core/localCache', () => ({ readCachedValue: () => null, writeCachedValue: () => {} }))
jest.mock('../../core/webPreferences', () => ({ useWebPreferences: () => [{}] }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: () => null, StreamingVideo: () => null }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))
jest.mock('../../components/SocialPromotion', () => ({ SocialPromotion: () => null }))
jest.mock('../../ui/novvin/SocialMoments', () => ({ SocialMoments: () => null }))

beforeEach(() => Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: randomUUID }))

test.each([0, 1, 2])('the comment count link has visible text and an accessible name for %s comments', async count => {
  ;(loadFallbackFeed as jest.Mock).mockResolvedValue({ items: [{ id: 'post', user_id: 'author', user_name: 'Autor', content: 'Publicación de prueba', comment_count: count }], hasMore: false })
  const { container } = render(<MemoryRouter><FeedPage /></MemoryRouter>)
  await screen.findByText('Publicación de prueba')
  const link = container.querySelector('.feed-stats a[href$="#comments"]')!
  expect(link).toHaveTextContent(count === 1 ? '1 comentario' : `${count} comentarios`)
  expect(link).toHaveAccessibleName(count === 1 ? '1 comentario' : `${count} comentarios`)
})
