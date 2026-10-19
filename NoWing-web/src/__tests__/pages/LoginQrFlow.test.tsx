import { StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { LoginPage } from '../../pages/auth/LoginPage'
import { persistFallbackSession } from '../../core/auth/fallbackAuth'
import { consumeApprovedWebLoginPairing, createWebLoginPairing, readWebLoginPairingStatus, toPairingError } from '../../core/auth/webLoginPairing'

jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../core/auth/sessionBootstrap', () => ({ signInWithResolvedEmail: jest.fn() }))
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true, persistFallbackSession: jest.fn() }))
jest.mock('../../core/webPreferences', () => ({ useWebPreferences: () => [{}] }))
jest.mock('../../components/QrCodeTile', () => ({ QrCodeTile: () => <div data-testid="pairing-qr">QR</div> }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: (props: React.ImgHTMLAttributes<HTMLImageElement>) => <img {...props} /> }))
jest.mock('../../core/auth/webLoginPairing', () => ({ createWebLoginPairing: jest.fn(), readWebLoginPairingStatus: jest.fn(), consumeApprovedWebLoginPairing: jest.fn(), toPairingError: jest.fn((_: unknown, fallback: string) => fallback) }))
const create = createWebLoginPairing as jest.Mock
const status = readWebLoginPairingStatus as jest.Mock
const consume = consumeApprovedWebLoginPairing as jest.Mock
const persisted = persistFallbackSession as jest.Mock
const pairing = { pairId: 'synthetic-pair', nonce: 'synthetic-nonce', qrUrl: 'conectate://web-login?pairId=synthetic-pair&nonce=synthetic-nonce', status: 'pending' }

function open() {
  return render(<StrictMode><MemoryRouter initialEntries={['/login?mode=qr']}><Routes><Route path="/login" element={<LoginPage />} /><Route path="/app/messages" element={<h1>Sesión iniciada</h1>} /></Routes></MemoryRouter></StrictMode>)
}

beforeEach(() => {
  jest.clearAllMocks()
  create.mockResolvedValue(pairing)
  status.mockResolvedValue({ ...pairing, status: 'approved' })
  consume.mockResolvedValue({ ...pairing, status: 'consumed', accessToken: 'synthetic-test-value' })
  ;(toPairingError as jest.Mock).mockImplementation((_:unknown,fallback:string)=>fallback)
})

test('a blocked browser permission is explained next to a single retry control',async()=>{
 const message='El navegador bloqueó la conexión con Novvin. Permite el acceso a la red local en los permisos de este sitio y vuelve a intentarlo.'
 ;(toPairingError as jest.Mock).mockReturnValue(message)
 create.mockRejectedValue(new Error('permission denied'))
 const view=open()
 await screen.findByText('QR no disponible')
 expect(screen.getByRole('alert')).toHaveTextContent(message)
 expect(screen.getByText(/Este permiso es distinto del acceso a la cámara/)).toBeVisible()
 expect(screen.getAllByRole('button',{name:'Reintentar QR'})).toHaveLength(1)
 expect(view.container.querySelector('.auth-qr-hero')).toContainElement(screen.getByRole('button',{name:'Reintentar QR'}))
 expect(consume).not.toHaveBeenCalled();expect(persisted).not.toHaveBeenCalled()
})

test('consumes the mobile approval once and navigates after its request completes', async () => {
  let finish!: (value: unknown) => void
  consume.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  open()
  await waitFor(() => expect(consume).toHaveBeenCalledTimes(1))
  expect(persisted).not.toHaveBeenCalled()
  await act(async () => finish({ ...pairing, status: 'consumed', accessToken: 'synthetic-test-value' }))
  await screen.findByRole('heading', { name: 'Sesión iniciada' })
  expect(persisted).toHaveBeenCalledTimes(1)
  expect(consume).toHaveBeenCalledTimes(1)
})

test('does not install a QR session after the person changes login mode', async () => {
  let finish!: (value: unknown) => void
  consume.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  open()
  await waitFor(() => expect(consume).toHaveBeenCalledTimes(1))
  fireEvent.click(screen.getByRole('button', { name: 'Contraseña', exact: true }))
  await screen.findByRole('heading', { name: 'Qué bueno verte' })
  await act(async () => finish({ ...pairing, status: 'consumed', accessToken: 'synthetic-test-value' }))
  expect(persisted).not.toHaveBeenCalled()
  expect(screen.queryByRole('heading', { name: 'Sesión iniciada' })).toBeNull()
})

test('removes an expired QR and lets the person request another code', async () => {
  status.mockResolvedValue({ ...pairing, status: 'expired' })
  open()
  await screen.findByText('El código caducó')
  expect(screen.queryByTestId('pairing-qr')).toBeNull()
  const before = create.mock.calls.length
  fireEvent.click(screen.getByRole('button', { name: 'Generar otro QR' }))
  await waitFor(() => expect(create.mock.calls.length).toBeGreaterThan(before))
  expect(consume).not.toHaveBeenCalled()
})

test('a failed QR request allows a manual retry and recovers when the service responds', async () => {
  create.mockRejectedValue(new Error('Synthetic offline service'))
  open()
  await screen.findByText('QR no disponible')
  expect(screen.getByRole('alert')).toHaveTextContent('No se pudo generar el QR de Novvin Web.')
  const retry = screen.getByRole('button', { name: 'Reintentar QR' })
  expect(retry).toBeEnabled()
  create.mockResolvedValue(pairing)
  status.mockResolvedValue(pairing)
  fireEvent.click(retry)
  await screen.findByTestId('pairing-qr')
  expect(screen.queryByRole('alert')).toBeNull()
})
