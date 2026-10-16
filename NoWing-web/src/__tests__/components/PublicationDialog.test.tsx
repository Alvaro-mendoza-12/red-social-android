import { fireEvent, render, screen } from '@testing-library/react'
import { PublicationDialog } from '../../components/PublicationDialog'
jest.mock('../../components/publicationDialog.css', () => ({}))
const show = HTMLDialogElement.prototype.showModal, close = HTMLDialogElement.prototype.close
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterAll(() => { HTMLDialogElement.prototype.showModal = show; HTMLDialogElement.prototype.close = close })

test('opens a native modal and restores the launch button after closing', () => {
  const launch = document.createElement('button'); document.body.append(launch); launch.focus()
  const onClose = jest.fn(), { unmount } = render(<PublicationDialog title="Crear directo" onClose={onClose}>Formulario</PublicationDialog>)
  const dialog = screen.getByRole('dialog', { name: 'Crear directo' }); expect(dialog).toHaveAttribute('open')
  fireEvent(dialog, new Event('cancel', { cancelable: true })); expect(onClose).toHaveBeenCalledTimes(1)
  unmount(); expect(document.activeElement).toBe(launch); launch.remove()
})

test('Escape and close cannot dismiss a pending publication', () => {
  const onClose = jest.fn(), { rerender } = render(<PublicationDialog title="Publicar video" busy onClose={onClose}>Formulario</PublicationDialog>)
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
  expect(screen.getByRole('button', { name: 'Cerrar publicar video' })).toBeDisabled(); expect(onClose).not.toHaveBeenCalled()
  rerender(<PublicationDialog title="Publicar video" onClose={onClose}>Formulario</PublicationDialog>)
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar publicar video' })); expect(onClose).toHaveBeenCalledTimes(1)
})
