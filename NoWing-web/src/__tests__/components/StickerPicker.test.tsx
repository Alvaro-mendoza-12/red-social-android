import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StickerPicker } from '../../components/StickerPicker'
import { supabase } from '../../core/supabase'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'

jest.mock('../../core/supabase', () => ({ supabase: { rpc: jest.fn() } }))
let mockFallback = false
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'owned-synthetic' }, isFallbackSession: mockFallback }) }))
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../components/ChatAttachmentMedia', () => ({ ChatAttachmentMedia: ({ title }: { title: string }) => <span>{title}</span> }))
const rpc = supabase!.rpc as jest.Mock
beforeEach(() => {
  mockFallback = false
  rpc.mockReset().mockResolvedValue({ data: [{ id: 'sticker-1', title: 'Hola', media_url: 'https://media.test/sticker.webp' }], error: null })
  ;(fallbackApiJson as jest.Mock).mockReset()
})

test('uses the primary server and binds the result to the current account', async () => {
  mockFallback = true
  ;(fallbackApiJson as jest.Mock).mockResolvedValue([{ sticker_id: 'primary-sticker', title: 'Servidor', media_url: 'https://media.test/primary.webp' }])
  const select = jest.fn()
  render(<StickerPicker onSelect={select} onImport={() => {}} onClose={() => {}} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Enviar sticker Servidor' }))
  expect(fallbackApiJson).toHaveBeenCalledWith('/rest/v1/rpc/secure_search_stickers', expect.objectContaining({ method: 'POST', expectedAccountId: 'owned-synthetic' }))
  expect(rpc).not.toHaveBeenCalled()
  expect(select).toHaveBeenCalledWith(expect.objectContaining({ stickerId: 'primary-sticker' }))
})

test('loads the mobile catalog immediately and sends its actual sticker identifier', async () => {
  const select = jest.fn()
  render(<StickerPicker onSelect={select} onImport={() => {}} onClose={() => {}} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Enviar sticker Hola' }))
  expect(rpc).toHaveBeenCalledWith('secure_search_stickers', { p_query: '', p_limit: 60 })
  expect(select).toHaveBeenCalledWith({ stickerId: 'sticker-1', title: 'Hola', mediaUrl: 'https://media.test/sticker.webp', isFavorite: false })
})

test('searches the same authorized catalog while the person types', async () => {
  render(<StickerPicker onSelect={() => {}} onImport={() => {}} onClose={() => {}} />)
  await screen.findByRole('button', { name: 'Enviar sticker Hola' })
  fireEvent.change(screen.getByRole('searchbox', { name: 'Buscar stickers' }), { target: { value: 'gato' } })
  await waitFor(() => expect(rpc).toHaveBeenLastCalledWith('secure_search_stickers', { p_query: 'gato', p_limit: 60 }))
})

test('discards a slow result from a previous search', async () => {
  let finish!: (value: unknown) => void
  rpc.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  render(<StickerPicker onSelect={() => {}} onImport={() => {}} onClose={() => {}} />)
  await waitFor(() => expect(rpc).toHaveBeenCalledTimes(1))
  fireEvent.change(screen.getByRole('searchbox', { name: 'Buscar stickers' }), { target: { value: 'nuevo' } })
  await screen.findByRole('button', { name: 'Enviar sticker Hola' })
  await act(async () => finish({ data: [{ id: 'old', title: 'Antiguo', media_url: 'https://media.test/old.webp' }], error: null }))
  expect(screen.queryByRole('button', { name: 'Enviar sticker Antiguo' })).toBeNull()
})
