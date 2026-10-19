import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { CommentComposer } from '../../components/CommentComposer'
import { supabase } from '../../core/supabase'

jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'commenter' }, isFallbackSession: false }) }))
jest.mock('../../core/supabase', () => ({ supabase: { rpc: jest.fn() } }))
jest.mock('../../core/fallbackData', () => ({ uploadFallbackFile: jest.fn(), searchFallbackProfiles: jest.fn().mockResolvedValue({ items: [] }) }))
jest.mock('../../components/ChatAttachmentMedia', () => ({ ChatAttachmentMedia: () => null }))
jest.mock('../../components/MentionSuggestions', () => ({ MentionSuggestions: () => null }))
jest.mock('../../components/SpellingSuggestions', () => ({ SpellingSuggestions: () => null }))

const rpc = supabase!.rpc as jest.Mock

beforeEach(() => {
  rpc.mockReset().mockResolvedValue({ data: [{ id: 'sticker-1', title: 'Hola', media_url: 'https://media.test/sticker.webp' }], error: null })
})

test('comment composer opens the picker, inserts emoji, and sends a catalog sticker', async () => {
  const onSend = jest.fn().mockResolvedValue(undefined)
  render(<CommentComposer kind="post" busy={false} onCancelReply={() => {}} onSend={onSend} />)

  fireEvent.click(screen.getByRole('button', { name: 'Emojis y stickers' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Emojis', exact: true }))
  fireEvent.click(screen.getByRole('button', { name: 'alegría sonrisa' }))
  expect(screen.getByRole('textbox', { name: 'Escribir comentario' })).toHaveValue('😀')

  fireEvent.click(screen.getByRole('button', { name: 'Emojis y stickers' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Enviar sticker Hola' }))
  fireEvent.click(screen.getByRole('button', { name: 'Enviar comentario' }))

  await waitFor(() => expect(onSend).toHaveBeenCalledWith({
    content: '😀', contentType: 'sticker', mediaUrl: 'https://media.test/sticker.webp'
  }))
})

test('audio comment sending controls are not offered', () => {
  render(<CommentComposer kind="video" busy={false} onCancelReply={() => {}} onSend={jest.fn()} />)
  expect(screen.queryByRole('button', { name: /audio|grabar|micrófono/i })).toBeNull()
})
