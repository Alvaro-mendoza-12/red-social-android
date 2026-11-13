import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StickerPicker } from '../../components/StickerPicker'
import { supabase } from '../../core/supabase'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'

jest.mock('../../core/supabase', () => ({ supabase: { rpc: jest.fn() } }))
let mockFallback = false
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'owned-synthetic' }, isFallbackSession: mockFallback }) }))
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../components/ChatAttachmentMedia', () => ({ ChatAttachmentMedia: ({ title, preserveAnimation }: { title: string; preserveAnimation: boolean }) => <span data-original={preserveAnimation}>{title}</span> }))
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
  expect(screen.getByText('Hola', { selector: 'span[data-original]' })).toHaveAttribute('data-original', 'true')
  expect(rpc).toHaveBeenCalledWith('secure_search_stickers', { p_query: '', p_limit: 250 })
  expect(select).toHaveBeenCalledWith({ stickerId: 'sticker-1', title: 'Hola', mediaUrl: 'https://media.test/sticker.webp', isFavorite: false, isAnimated: false })
})

test('searches the same authorized catalog while the person types', async () => {
  render(<StickerPicker onSelect={() => {}} onImport={() => {}} onClose={() => {}} />)
  await screen.findByRole('button', { name: 'Enviar sticker Hola' })
  fireEvent.change(screen.getByRole('searchbox', { name: 'Buscar stickers' }), { target: { value: 'gato' } })
  await waitFor(() => expect(rpc).toHaveBeenLastCalledWith('secure_search_stickers', { p_query: 'gato', p_limit: 250 }))
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

test('uses the mobile animation flag even when the URL has no animation extension', async () => {
  rpc.mockResolvedValue({data:[{id:'animated',title:'En movimiento',media_url:'https://media.test/opaque',is_animated:true},{id:'still',title:'Fijo',media_url:'https://media.test/still.webp',is_animated:false}],error:null})
  render(<StickerPicker onSelect={() => {}} onImport={() => {}} onClose={() => {}} />)
  await screen.findByRole('button',{name:'Enviar sticker Fijo'})
  fireEvent.click(screen.getByRole('button',{name:'Animados',exact:true}))
  expect(screen.getByRole('button',{name:'Enviar sticker En movimiento'})).toBeInTheDocument();expect(screen.queryByRole('button',{name:'Enviar sticker Fijo'})).toBeNull()
  fireEvent.contextMenu(screen.getByRole('button',{name:'Enviar sticker En movimiento'}), {clientX:100,clientY:100})
  fireEvent.click(screen.getByRole('menuitem',{name:'Guardar en favoritos'}))
  await waitFor(()=>expect(rpc).toHaveBeenCalledWith('secure_toggle_sticker_favorite',expect.objectContaining({p_is_animated:true})))
  fireEvent.click(screen.getByRole('button',{name:'Todos',exact:true}))
  expect(screen.getByRole('button',{name:'Animados',exact:true})).toHaveAttribute('aria-pressed','false')
  expect(screen.getByRole('button',{name:'Enviar sticker Fijo'})).toBeInTheDocument()
})

test('refreshes favorites saved from another device without reopening the chat', async () => {
  render(<StickerPicker onSelect={() => {}} onImport={() => {}} onClose={() => {}} />)
  await screen.findByRole('button',{name:'Enviar sticker Hola'})
  rpc.mockResolvedValueOnce({data:[{id:'from-mobile',title:'Del móvil',media_url:'https://media.test/mobile.gif',is_animated:true,is_favorite:true}],error:null})
  fireEvent.click(screen.getByRole('button',{name:'Actualizar stickers'}))
  await screen.findByRole('button',{name:'Enviar sticker Del móvil'})
  fireEvent.click(screen.getByRole('button',{name:'Favoritos',exact:true}))
  fireEvent.contextMenu(screen.getByRole('button',{name:'Enviar sticker Del móvil'}))
  expect(screen.getByRole('menuitem',{name:'Quitar de favoritos'})).toBeInTheDocument()
})


test('keeps tiles free of stars and exposes favorites in one keyboard menu', async () => {
  const select = jest.fn()
  render(<StickerPicker onSelect={select} onImport={() => {}} onClose={() => {}} />)
  const tile = await screen.findByRole('button', {name:'Enviar sticker Hola'})
  expect(document.querySelector('.sticker-save-button')).toBeNull()
  fireEvent.keyDown(tile, {key:'F10', shiftKey:true})
  expect(screen.getAllByRole('menu')).toHaveLength(1)
  expect(screen.getByRole('menuitem',{name:'Enviar sticker'})).toHaveFocus()
  fireEvent.keyDown(screen.getByRole('menu'),{key:'ArrowDown'})
  expect(screen.getByRole('menuitem',{name:'Guardar en favoritos'})).toHaveFocus()
  fireEvent.keyDown(document,{key:'Escape'})
  expect(screen.queryByRole('menu')).toBeNull()
  expect(tile).toHaveFocus()
  expect(select).not.toHaveBeenCalled()
})

test('closes sticker options on outside click', async () => {
  render(<StickerPicker onSelect={() => {}} onImport={() => {}} onClose={() => {}} />)
  fireEvent.contextMenu(await screen.findByRole('button', {name:'Enviar sticker Hola'}))
  fireEvent.pointerDown(document.body)
  expect(screen.queryByRole('menu')).toBeNull()
})
