import { act, fireEvent, render, screen } from '@testing-library/react'
import { ShareVideoDialog } from '../../components/ShareVideoDialog'
import { ForwardMessagesDialog } from '../../components/ForwardMessagesDialog'
import { invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'
import { loadFallbackMessagesInbox, sendFallbackChatMessage } from '../../core/fallbackData'

jest.mock('../../core/fallbackData', () => ({ loadFallbackMessagesInbox: jest.fn(), sendFallbackChatMessage: jest.fn(), sendFallbackRoomMessage: jest.fn() }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))
const show = HTMLDialogElement.prototype.showModal, close = HTMLDialogElement.prototype.close
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); this.focus() }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterAll(() => { HTMLDialogElement.prototype.showModal = show; HTMLDialogElement.prototype.close = close })
beforeEach(() => {
  jest.resetAllMocks()
  updateStorageReadIdentity('fixture-account', 'fallback')
  ;(loadFallbackMessagesInbox as jest.Mock).mockImplementation(async (tab: string) => ({ items: tab === 'chats' ? [{ id: 'recipient', kind: 'chat', title: 'Amiga', isMember: true }] : [] }))
})

afterEach(() => invalidateStorageReadIdentity())

describe.each(['share', 'forward'])('%s dialog', kind => {
  function mount(onClose: () => void) {
    return render(kind === 'share' ? <ShareVideoDialog videoId="video" title="Video" onClose={onClose} /> : <ForwardMessagesDialog messages={[{ id: 'message', senderId: 'sender', content: 'Hola', contentType: 'text', mediaUrl: null, deletedForAll: false }]} onClose={onClose} />)
  }
  test('opens as a native modal, handles Escape and restores the trigger', async () => {
    const trigger = document.createElement('button'); document.body.append(trigger); trigger.focus()
    const onClose = jest.fn(), { unmount } = mount(onClose)
    await screen.findByRole('button', { name: /Amiga/ })
    const dialog = screen.getByRole('dialog')
    expect(dialog.tagName).toBe('DIALOG')
    expect(dialog).toHaveAttribute('open')
    expect(screen.getByRole('textbox', { name: 'Buscar destinatarios' })).toBeVisible()
    fireEvent(dialog, new Event('cancel', { cancelable: true }))
    expect(onClose).toHaveBeenCalledTimes(1)
    unmount(); expect(document.activeElement).toBe(trigger); trigger.remove()
  })
  test('cannot be dismissed while a send is pending', async () => {
    ;(sendFallbackChatMessage as jest.Mock).mockImplementation(() => new Promise(() => {}))
    const onClose = jest.fn(); mount(onClose)
    fireEvent.click(await screen.findByRole('button', { name: /Amiga/ }))
    fireEvent.click(screen.getByRole('button', { name: kind === 'share' ? 'Enviar (1)' : 'Reenviar a 1' }))
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /Cerrar/ })).toBeDisabled()
  })

  test('closing the active session hides the old dialog', async () => {
    const onClose = jest.fn()
    mount(onClose)
    await screen.findByRole('button', { name: /Amiga/ })

    act(() => invalidateStorageReadIdentity())

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  test('changing sessions stops the remaining sends in an open batch', async () => {
    jest.mocked(loadFallbackMessagesInbox).mockImplementation(async tab => ({
      items: tab === 'chats' ? [
        { id: 'first-chat', kind: 'chat', title: 'Primera conversación', isMember: true },
        { id: 'second-chat', kind: 'chat', title: 'Segunda conversación', isMember: true },
      ] : [],
    }) as Awaited<ReturnType<typeof loadFallbackMessagesInbox>>)
    let finishFirst: () => void
    jest.mocked(sendFallbackChatMessage).mockImplementationOnce(() => new Promise(resolve => {
      finishFirst = () => resolve({ message: { id: 'fixture-message' } })
    }))
    const onClose = jest.fn()
    mount(onClose)
    fireEvent.click(await screen.findByRole('button', { name: /Primera conversación/ }))
    fireEvent.click(screen.getByRole('button', { name: /Segunda conversación/ }))
    fireEvent.click(screen.getByRole('button', { name: kind === 'share' ? 'Enviar (2)' : 'Reenviar a 2' }))
    expect(sendFallbackChatMessage).toHaveBeenCalledTimes(1)

    await act(async () => {
      updateStorageReadIdentity('another-fixture-account', 'fallback')
      finishFirst()
    })

    expect(sendFallbackChatMessage).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

})
