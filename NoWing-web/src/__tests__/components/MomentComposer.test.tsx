import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MomentComposer } from '../../features/moments/MomentComposer'
import { publishMoment } from '../../features/moments/momentsData'
import { searchMomentMusic } from '../../features/moments/momentMusic'
jest.mock('../../features/moments/momentComposer.css', () => ({}))
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'owner-a' } }) }))
jest.mock('../../features/moments/momentsData', () => ({ publishMoment: jest.fn() }))
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../features/moments/momentMusic', () => ({ ...jest.requireActual('../../features/moments/momentMusic'), searchMomentMusic: jest.fn() }))
jest.mock('../../components/StreamingMedia', () => { const React = jest.requireActual('react'); return { StreamingAudio: React.forwardRef((p: object, ref: unknown) => React.createElement('audio', { ...p, ref })), StreamingVideo: React.forwardRef((p: object, ref: unknown) => React.createElement('video', { ...p, ref })), StreamingImage: (p: object) => React.createElement('img', p) } })
const publish = publishMoment as jest.Mock, search = searchMomentMusic as jest.Mock
beforeEach(() => {
  publish.mockReset(); publish.mockResolvedValue(undefined); search.mockReset(); search.mockResolvedValue({ tracks: [], nextCursor: '' })
  Object.assign(URL, { createObjectURL: jest.fn(() => 'blob:local-photo'), revokeObjectURL: jest.fn() })
  jest.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
})
afterEach(() => jest.restoreAllMocks())

test.each(['button', 'escape', 'backdrop'])('warns before discarding a draft through %s', async method => {
  const close = jest.fn(), confirm = jest.spyOn(window, 'confirm').mockReturnValue(false)
  const { container } = render(<MomentComposer onClose={close} onPublished={() => {}} />)
  fireEvent.change(screen.getByLabelText('Descripción'), { target: { value: 'Conservar borrador' } })
  const dismiss = () => {
    if (method === 'button') fireEvent.click(screen.getByRole('button', { name: 'Cerrar' }))
    else if (method === 'escape') fireEvent.keyDown(window, { key: 'Escape' })
    else fireEvent.mouseDown(container.querySelector('.app-dialog-backdrop')!)
  }
  dismiss()
  expect(confirm).toHaveBeenCalledTimes(1)
  expect(close).not.toHaveBeenCalled()
  expect(screen.getByLabelText('Descripción')).toHaveValue('Conservar borrador')
  confirm.mockReturnValue(true); dismiss(); expect(close).toHaveBeenCalledTimes(1)
})

test('an untouched moment closes without a discard prompt', () => {
  const close = jest.fn(), confirm = jest.spyOn(window, 'confirm').mockReturnValue(false)
  render(<MomentComposer onClose={close} onPublished={() => {}} />)
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar' }))
  expect(close).toHaveBeenCalledTimes(1); expect(confirm).not.toHaveBeenCalled()
})

test('a selected photo protects the draft against page refresh', () => {
  render(<MomentComposer onClose={() => {}} onPublished={() => {}} />)
  pickPhoto()
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
})
const pickPhoto = () => fireEvent.change(screen.getByLabelText('Foto o video'), { target: { files: [new File(['image'], 'foto.png', { type: 'image/png' })] } })
test('choosing a file only previews it; explicit publication is required', async () => {
  const done = jest.fn(); render(<MomentComposer onClose={() => {}} onPublished={done} />)
  pickPhoto(); await screen.findByAltText('Vista previa del momento')
  expect(publish).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('Quién puede verlo'), { target: { value: 'private' } })
  fireEvent.click(screen.getByRole('button', { name: 'Publicar momento' }))
  await waitFor(() => expect(done).toHaveBeenCalledTimes(1))
  expect(publish.mock.calls[0][0]).toMatchObject({ visibility: 'private', displayDurationMs: 6000, expectedAccountId: 'owner-a' })
})
test('publication failure retains the draft and selected image', async () => {
  publish.mockRejectedValue(new Error('No se pudo publicar'))
  render(<MomentComposer onClose={() => {}} onPublished={() => {}} />); pickPhoto()
  fireEvent.change(screen.getByLabelText('Descripción'), { target: { value: 'Mi descripción' } })
  fireEvent.click(screen.getByRole('button', { name: 'Publicar momento' }))
  await screen.findByRole('alert')
  expect(screen.getByLabelText('Descripción')).toHaveValue('Mi descripción')
  expect(screen.getByAltText('Vista previa del momento')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Publicar momento' })).toBeEnabled()
})
test('community music permits seeking beyond six seconds into the original song', async () => {
  search.mockResolvedValue({ tracks: [{ id: 'community:test', title: 'Canción completa', artist: 'Persona', durationMs: 180000, audioUrl: 'https://media.test/full.mp3', source: 'novvin_community' }], nextCursor: '' })
  render(<MomentComposer onClose={() => {}} onPublished={() => {}} />); pickPhoto()
  fireEvent.click(screen.getByRole('button', { name: 'Música de comunidad' }))
  fireEvent.click(await screen.findByRole('button', { name: /Canción completa/ }))
  const slider = screen.getByRole('slider', { name: 'Inicio del fragmento' })
  expect(slider).toHaveAttribute('max', '174000')
  fireEvent.change(slider, { target: { value: '120000' } })
  fireEvent.click(screen.getByRole('button', { name: 'Publicar momento' }))
  await waitFor(() => expect(publish).toHaveBeenCalledTimes(1))
  expect(publish.mock.calls[0][0].music.startMs).toBe(120000)
})
test('a late pagination result is discarded after the search changes', async () => {
  const track = { id: 'community:old', title: 'Antigua', artist: 'Persona', durationMs: 180000, audioUrl: 'https://media.test/full.mp3', source: 'novvin_community' }
  let finish!: (result: unknown) => void
  search.mockResolvedValueOnce({ tracks: [track], nextCursor: 'more' }).mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValue({ tracks: [], nextCursor: '' })
  render(<MomentComposer onClose={() => {}} onPublished={() => {}} />)
  fireEvent.click(screen.getByRole('button', { name: 'Música de comunidad' })); await screen.findByRole('button', { name: /Antigua/ })
  fireEvent.click(screen.getByRole('button', { name: 'Ver más canciones' }))
  fireEvent.change(screen.getByPlaceholderText('Canción o artista'), { target: { value: 'Nueva' } })
  await act(async () => finish({ tracks: [{ ...track, id: 'community:late', title: 'Respuesta atrasada' }], nextCursor: '' }))
  await waitFor(() => expect(search).toHaveBeenCalledTimes(3))
  expect(screen.queryByRole('button', { name: /Respuesta atrasada/ })).not.toBeInTheDocument()
})
