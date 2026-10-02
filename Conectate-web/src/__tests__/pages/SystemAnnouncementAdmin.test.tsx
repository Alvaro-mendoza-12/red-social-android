jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../components/systemAnnouncements.css', () => ({}))
jest.mock('../../core/systemAnnouncements', () => ({
  loadSystemAnnouncements: jest.fn().mockResolvedValue([]), publishSystemAnnouncement: jest.fn().mockResolvedValue({}),
  revokeSystemAnnouncement: jest.fn(), activeAnnouncement: jest.fn(), announcementSchedule: () => '',
}))
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { SystemAnnouncementAdmin } from '../../pages/admin/SystemAnnouncementAdmin'
import { publishSystemAnnouncement } from '../../core/systemAnnouncements'

beforeEach(() => {
  jest.clearAllMocks()
  HTMLDialogElement.prototype.showModal = jest.fn()
  Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: () => 'fixture-notice-id' })
})
test('an administrator reviews before publishing; readonly access has no publish button', async () => {
  const view = render(<SystemAnnouncementAdmin canManage={false} />)
  await screen.findByText('No hay avisos publicados.')
  expect(screen.queryByRole('button', { name: 'Revisar antes de publicar' })).not.toBeInTheDocument()
  view.rerender(<SystemAnnouncementAdmin canManage />)
  fireEvent.change(screen.getByLabelText('Título'), { target: { value: 'Aviso aislado' } })
  fireEvent.change(screen.getByLabelText('Mensaje para los usuarios'), { target: { value: 'Prueba sin destinatarios reales.' } })
  fireEvent.click(screen.getByRole('button', { name: 'Revisar antes de publicar' }))
  expect(publishSystemAnnouncement).not.toHaveBeenCalled()
  expect(screen.getByText('Publicar para todas las cuentas')).toBeInTheDocument()
  fireEvent.click(screen.getByText('Publicar aviso'))
  await waitFor(() => expect(publishSystemAnnouncement).toHaveBeenCalledTimes(1))
  expect(jest.mocked(publishSystemAnnouncement).mock.calls[0][0].id).toBe('fixture-notice-id')
})
