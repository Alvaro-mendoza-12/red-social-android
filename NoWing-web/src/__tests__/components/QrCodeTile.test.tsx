import { act, render, screen, waitFor } from '@testing-library/react'
import QRCode from 'qrcode'
import { QrCodeTile } from '../../components/QrCodeTile'

jest.mock('qrcode', () => ({ __esModule: true, default: { toDataURL: jest.fn() } }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: (props: React.ImgHTMLAttributes<HTMLImageElement>) => <img {...props} /> }))
const generate = QRCode.toDataURL as jest.Mock

beforeEach(() => generate.mockReset().mockResolvedValue('data:image/png;base64,synthetic'))

test('uses a readable quiet zone and high resolution for camera scanning', async () => {
  render(<QrCodeTile value="conectate://web-login?pairId=synthetic&nonce=synthetic" />)
  await screen.findByAltText('Código QR')
  expect(generate).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({ width: 320, margin: 4, errorCorrectionLevel: 'M' }))
})

test('removes the previous QR while its replacement is being generated', async () => {
  const { rerender } = render(<QrCodeTile value="first" />)
  await screen.findByAltText('Código QR')
  let resolve!: (value: string) => void
  generate.mockImplementationOnce(() => new Promise<string>(done => { resolve = done }))
  rerender(<QrCodeTile value="second" />)
  await waitFor(() => expect(screen.queryByAltText('Código QR')).toBeNull())
  await act(async () => resolve('data:image/png;base64,second'))
  expect(screen.getByAltText('Código QR')).toHaveAttribute('src', 'data:image/png;base64,second')
})

test('does not expose QR library error details', async () => {
  generate.mockRejectedValueOnce(new Error('internal stack with implementation details'))
  render(<QrCodeTile value="value" />)
  await screen.findByText('No se pudo preparar el código. Inténtalo de nuevo.')
  expect(screen.queryByText(/internal stack/)).toBeNull()
})
