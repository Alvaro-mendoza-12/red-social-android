import { fireEvent, render, screen } from '@testing-library/react'
import { ChatMediaAlbum } from '../../components/ChatMediaAlbum'
jest.mock('../../components/ChatAttachmentMedia', () => ({ ChatAttachmentMedia: ({ title, onImageOpen }: { title: string; onImageOpen: () => void }) => <button aria-label={`Ampliar ${title}`} onClick={onImageOpen}><img alt={title} /></button> }))
jest.mock('../../components/MediaViewer', () => ({ MediaViewer: ({ items, initialIndex }: { items: unknown[]; initialIndex: number }) => <div role="dialog">{items.length} fotos; inicial {initialIndex}</div> }))
const items = Array.from({ length: 5 }, (_, index) => ({ id: String(index), senderId: 'owner', contentType: 'image', content: 'photo.png', mediaUrl: 'https://media.test/photo.png', createdAt: '2026-10-15T12:00:00Z', replyToId: null, deletedForAll: false, deletedBy: [] }))
const actions = () => ({ onReply: jest.fn(), onForward: jest.fn(), onDelete: jest.fn(), onDeleteForAll: jest.fn(), onReact: jest.fn() })
test('shows four thumbnails, the remaining count and all five images in the viewer', () => {
  render(<ChatMediaAlbum items={items} mine {...actions()} />)
  expect(screen.getAllByRole('img')).toHaveLength(4)
  expect(screen.getByText('+1')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Ampliar foto 3 de 5' }))
  expect(screen.getByRole('dialog')).toHaveTextContent('5 fotos; inicial 2')
})
test('retains reply and delete controls for each original message', () => {
  const callbacks = actions()
  render(<ChatMediaAlbum items={items} mine {...callbacks} />)
  fireEvent.click(screen.getAllByRole('button', { name: 'Responder', hidden: true })[4])
  fireEvent.click(screen.getAllByRole('button', { name: 'Eliminar para todos', hidden: true })[2])
  expect(callbacks.onReply).toHaveBeenCalledWith(items[4])
  expect(callbacks.onDeleteForAll).toHaveBeenCalledWith(items[2])
})
test('does not offer deletion for everyone for received photos', () => {
  render(<ChatMediaAlbum items={items} mine={false} {...actions()} />)
  expect(screen.queryByRole('button', { name: 'Eliminar para todos', hidden: true })).toBeNull()
})

test('an album is only marked as read when every original photo is read', () => {
  const view = render(<ChatMediaAlbum items={items} mine deliveryState={message => message.id === '4' ? 'sent' : 'read'} {...actions()} />)
  expect(screen.getByLabelText('Enviado')).toBeInTheDocument()
  view.rerender(<ChatMediaAlbum items={items} mine deliveryState={() => 'read'} {...actions()} />)
  expect(screen.getByLabelText('Leído')).toBeInTheDocument()
})
