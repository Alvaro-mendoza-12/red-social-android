import { fireEvent, render, screen } from '@testing-library/react'
import { RoomChatPhoto } from '../../features/voice/RoomChatPhoto'

test('opens the session image in an accessible modal and closes without leaving the room', () => {
  const show = jest.fn(function(this: HTMLDialogElement) { this.open = true })
  const close = jest.fn(function(this: HTMLDialogElement) { this.open = false })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: show })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: close })
  render(<RoomChatPhoto source="data:image/jpeg;base64,/9j/2Q==" author="Ana" />)
  fireEvent.click(screen.getByRole('button', { name: 'Ampliar foto de Ana' }))
  expect(show).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('dialog', { name: 'Foto de Ana', hidden: true })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar foto', hidden: true }))
  expect(close).toHaveBeenCalledTimes(1)
})
