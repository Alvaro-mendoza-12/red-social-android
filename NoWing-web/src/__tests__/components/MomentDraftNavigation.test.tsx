import { randomUUID } from 'node:crypto'
import { StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { MomentsPage } from '../../features/moments/MomentsPage'
import { fetchMomentsFeed, publishMoment } from '../../features/moments/momentsData'
import { localAudioDuration } from '../../features/moments/localMomentAudio'
import { searchMomentMusic } from '../../features/moments/momentMusic'
import { invalidateStorageReadBackend, invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'
jest.mock('../../features/moments/localMomentAudio', () => ({ localAudioDuration: jest.fn() }))
let mockOwner: string | null = 'owner-a'
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockOwner ? { id: mockOwner } : null }) }))
jest.mock('../../features/moments/momentsData', () => ({ publishMoment: jest.fn(), fetchMomentsFeed: jest.fn().mockResolvedValue([]), removeMoment: jest.fn() }))
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../core/supabase', () => ({ supabase: null, reportSupabaseServiceRestrictionIfPresent: jest.fn() }))
jest.mock('../../core/ubuntuBackend', () => ({ isUbuntuPrimary: true }))
jest.mock('../../core/ubuntuRealtime', () => ({ subscribeUbuntuRealtime: () => () => {} }))
jest.mock('../../features/moments/momentMusic', () => ({ ...jest.requireActual('../../features/moments/momentMusic'), searchMomentMusic: jest.fn() }))
jest.mock('../../components/StreamingMedia', () => { const React = jest.requireActual('react'); return { StreamingAudio: React.forwardRef((p: { src?: string }, ref: unknown) => React.createElement('audio', { ...p, src: p.src || undefined, ref })), StreamingVideo: React.forwardRef((p: object, ref: unknown) => React.createElement('video', { ...p, ref })), StreamingImage: (p: object) => React.createElement('img', p) } })
const publish = publishMoment as jest.Mock, search = searchMomentMusic as jest.Mock
function Navigation() {
  const navigate = useNavigate()
  return <><button onClick={() => navigate(-1)}>Atrás</button><button onClick={() => navigate(1)}>Adelante</button><button onClick={() => navigate('/other')}>Otra sección</button><button onClick={() => navigate('/app/moments')}>Momentos</button></>
}
function App() {
  return <StrictMode><MemoryRouter initialEntries={['/other', '/app/moments']} initialIndex={1}><Navigation /><Routes><Route path="/app/moments" element={<MomentsPage />} /><Route path="/other" element={<p>Otra página</p>} /></Routes></MemoryRouter></StrictMode>
}
const photo = new File(['image'], 'foto.png', { type: 'image/png' })
const pickPhoto = () => fireEvent.change(screen.getByLabelText('Foto o video'), { target: { files: [photo] } })
const go = async (name: string) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
const resume = () => go('Continuar borrador')
async function open() { await act(async () => { render(<App />) }); await go('Crear') }
beforeEach(() => {
  invalidateStorageReadIdentity(); mockOwner = 'owner-a'; updateStorageReadIdentity(mockOwner, 'fallback')
  ;(fetchMomentsFeed as jest.Mock).mockReset(); (fetchMomentsFeed as jest.Mock).mockResolvedValue([])
  publish.mockReset(); publish.mockResolvedValue(undefined); search.mockReset(); search.mockResolvedValue({ tracks: [], nextCursor: '' })
  Object.assign(crypto, { randomUUID })
  Object.assign(URL, { createObjectURL: jest.fn(() => 'blob:local-photo'), revokeObjectURL: jest.fn() })
  jest.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
})
afterEach(() => { act(() => invalidateStorageReadIdentity()); jest.restoreAllMocks() })
test.each(['Atrás', 'Otra sección'])('preserves fields and media through %s, with explicit resume and publication', async navigation => {
  await open(); pickPhoto()
  fireEvent.change(screen.getByLabelText('Descripción'), { target: { value: 'Privado, aún sin publicar' } })
  fireEvent.change(screen.getByLabelText('Quién puede verlo'), { target: { value: 'private' } })
  fireEvent.change(screen.getByLabelText('Visible durante'), { target: { value: '6' } })
  fireEvent.change(screen.getByLabelText('Texto superpuesto'), { target: { value: 'Mi texto' } })
  fireEvent.click(screen.getByLabelText('Permitir respuestas')); await go(navigation)
  expect(screen.getByText('Otra página')).toBeInTheDocument()
  const unload = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(unload)
  expect(unload.defaultPrevented).toBe(true)
  await go(navigation === 'Atrás' ? 'Adelante' : 'Momentos')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); await resume()
  expect(screen.getByLabelText('Descripción')).toHaveValue('Privado, aún sin publicar')
  expect(screen.getByLabelText('Quién puede verlo')).toHaveValue('private')
  expect(screen.getByLabelText('Visible durante')).toHaveValue('6')
  expect(screen.getByLabelText('Texto superpuesto')).toHaveValue('Mi texto')
  expect(screen.getByLabelText('Permitir respuestas')).not.toBeChecked()
  expect(screen.getByAltText('Vista previa del momento')).toBeInTheDocument(); expect(publish).not.toHaveBeenCalled()
  await go('Publicar momento'); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(publish).toHaveBeenCalledTimes(1)
  expect(publish.mock.calls[0][0].file).toBe(photo)
  expect(publish.mock.calls[0][0]).toMatchObject({ caption: 'Privado, aún sin publicar', visibility: 'private', durationHours: 6, allowReplies: false, expectedAccountId: 'owner-a' })
  await go('Crear'); expect(screen.getByLabelText('Descripción')).toHaveValue(''); expect(screen.queryByAltText('Vista previa del momento')).not.toBeInTheDocument()
})
test('restores community music and clip settings after navigation', async () => {
  search.mockResolvedValue({ tracks: [{ id: 'community:test', title: 'Canción completa', artist: 'Persona', durationMs: 180000, audioUrl: 'https://media.test/full.mp3', source: 'novvin_community' }], nextCursor: '' })
  await open(); pickPhoto(); await go('Música de comunidad'); fireEvent.click(await screen.findByRole('button', { name: /Canción completa/ }))
  fireEvent.change(screen.getByRole('slider', { name: 'Inicio del fragmento' }), { target: { value: '120000' } })
  fireEvent.change(screen.getByLabelText('Volumen de la música'), { target: { value: '.3' } })
  await go('Atrás'); await go('Adelante'); await resume()
  expect(screen.getByRole('slider', { name: 'Inicio del fragmento' })).toHaveValue('120000')
  expect(screen.getByLabelText('Volumen de la música')).toHaveValue('0.3'); expect(publish).not.toHaveBeenCalled()
})
test.each(['logout', 'account', 'backend'])('clears a hidden draft on %s, even after returning to the original account', async change => {
  await open(); pickPhoto(); fireEvent.change(screen.getByLabelText('Descripción'), { target: { value: 'Cuenta A secreta' } }); await go('Atrás')
  act(() => {
    if (change === 'backend') invalidateStorageReadBackend()
    else { mockOwner = change === 'logout' ? null : 'owner-b'; updateStorageReadIdentity(mockOwner, mockOwner ? 'fallback' : '') }
  })
  act(() => { mockOwner = 'owner-a'; updateStorageReadIdentity(mockOwner, 'fallback') }); await go('Adelante'); await go('Crear')
  expect(screen.getByLabelText('Descripción')).toHaveValue(''); expect(screen.queryByAltText('Vista previa del momento')).not.toBeInTheDocument(); expect(publish).not.toHaveBeenCalled()
})
test('confirmed discard releases the draft, cancellation keeps it', async () => {
  const confirm = jest.spyOn(window, 'confirm').mockReturnValue(false)
  await open(); pickPhoto(); fireEvent.change(screen.getByLabelText('Descripción'), { target: { value: 'Borrador' } })
  await go('Cerrar'); expect(screen.getByLabelText('Descripción')).toHaveValue('Borrador')
  confirm.mockReturnValue(true); await go('Cerrar'); await go('Crear'); expect(screen.getByLabelText('Descripción')).toHaveValue('')
  const unload = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(unload); expect(unload.defaultPrevented).toBe(false)
})
test.each(['success', 'failure'])('does not duplicate publication across Back/Forward; %s is reconciled', async result => {
  let resolve!: () => void, reject!: (error: Error) => void
  publish.mockImplementation(() => new Promise<void>((done, fail) => { resolve = done; reject = fail }))
  await open(); pickPhoto(); fireEvent.change(screen.getByLabelText('Descripción'), { target: { value: 'Una publicación' } }); await go('Publicar momento')
  await go('Atrás'); await go('Adelante'); await go('Ver publicación en curso')
  expect(screen.getByRole('button', { name: 'Publicando…' })).toBeDisabled()
  fireEvent.submit(screen.getByLabelText('Descripción').closest('form')!); expect(publish).toHaveBeenCalledTimes(1)
  await act(async () => { if (result === 'success') resolve(); else reject(new Error('Sin conexión')) })
  if (result === 'success') { await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); expect(screen.getByRole('button', { name: 'Crear' })).toBeInTheDocument() }
  else { expect(screen.getByLabelText('Descripción')).toHaveValue('Una publicación'); expect(screen.getByAltText('Vista previa del momento')).toBeInTheDocument(); expect(screen.getByRole('alert')).toBeInTheDocument(); expect(screen.getByRole('button', { name: 'Publicar momento' })).toBeEnabled() }
})
test('late success from an old session cannot erase the new session draft', async () => {
  let resolve!: () => void
  publish.mockImplementation(() => new Promise<void>(done => { resolve = done }))
  await open(); pickPhoto(); await go('Publicar momento'); await go('Atrás')
  act(() => { invalidateStorageReadIdentity(); updateStorageReadIdentity('owner-a', 'fallback') })
  await go('Adelante'); await go('Crear'); fireEvent.change(screen.getByLabelText('Descripción'), { target: { value: 'Sesión nueva' } })
  await act(async () => resolve()); expect(screen.getByLabelText('Descripción')).toHaveValue('Sesión nueva')
})


test('local audio metadata reading survives navigation and stays locked until ready', async () => {
  let resolve!: (duration: number) => void
  ;(localAudioDuration as jest.Mock).mockImplementation(() => new Promise<number>(done => { resolve = done }))
  const audio = new File(['sound'], 'audio.mp3', { type: 'audio/mpeg' })
  await open(); pickPhoto()
  fireEvent.change(screen.getByLabelText('Audio del dispositivo'), { target: { files: [audio] } })
  await go('Atrás'); await go('Adelante'); await resume()
  expect(screen.getByText('Abriendo audio…')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Publicar momento' })).toBeDisabled()
  await act(async () => resolve(180000))
  expect(screen.getByText('audio · Mi audio')).toBeInTheDocument()
  expect(publish).not.toHaveBeenCalled()
  await go('Publicar momento')
  expect(publish.mock.calls[0][0].music.track.localFile).toBe(audio)
})
test('discard cancels pending local audio and a late read cannot resurrect it', async () => {
  let resolve!: (duration: number) => void
  ;(localAudioDuration as jest.Mock).mockImplementation(() => new Promise<number>(done => { resolve = done }))
  jest.spyOn(window, 'confirm').mockReturnValue(true)
  await open()
  fireEvent.change(screen.getByLabelText('Audio del dispositivo'), { target: { files: [new File(['sound'], 'discarded.mp3', { type: 'audio/mpeg' })] } })
  await go('Cerrar'); await go('Crear'); await act(async () => resolve(180000))
  expect(screen.queryByText('discarded · Mi audio')).not.toBeInTheDocument()
  const unload = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(unload); expect(unload.defaultPrevented).toBe(false)
})


test('refreshes the feed when publication completes after returning without reopening the editor', async () => {
  let resolve!: () => void
  publish.mockImplementation(() => new Promise<void>(done => { resolve = done }))
  await open(); pickPhoto(); await go('Publicar momento'); await go('Atrás'); await go('Adelante')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  ;(fetchMomentsFeed as jest.Mock).mockResolvedValue([{ id: 'published', userId: 'owner-a', ownerName: 'Yo', mediaUrl: 'https://media.test/photo.png', mediaType: 'image', caption: 'Momento publicado una sola vez', visibility: 'private', allowReplies: false, createdAt: '2026-11-10T10:00:00Z', expiresAt: '2026-11-11T10:00:00Z', viewsCount: 0, viewedByMe: false, viewerPreview: [] }])
  await act(async () => resolve())
  expect(await screen.findByText('Momento publicado una sola vez')).toBeInTheDocument()
  expect(publish).toHaveBeenCalledTimes(1)
})
