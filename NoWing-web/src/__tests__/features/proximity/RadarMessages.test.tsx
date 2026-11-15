import { fireEvent, render, screen } from '@testing-library/react'
import { RadarMessages } from '../../../features/proximity/RadarMessages'
import { fallbackApiJson } from '../../../core/auth/fallbackAuth'

jest.mock('../../../core/auth/AuthProvider', () => ({
  useAuth: () => ({ user: { id: 'recipient' } }),
}))
jest.mock('../../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))

test('renders a bottle returned by availability even while the outgoing read is pending', async () => {
  jest.mocked(fallbackApiJson).mockImplementation(path => {
    if (path.endsWith('/availability')) {
      return Promise.resolve({ status: 'offered', bottle: { id: 'incoming', content: 'Hola desde el mar', status: 'offered' } })
    }
    return new Promise(() => {})
  })
  render(<RadarMessages onAccepted={jest.fn()} />)
  fireEvent.click(screen.getByRole('checkbox', { name: 'Recibir mensajes mientras estoy aquí' }))
  expect(await screen.findByText('Hola desde el mar')).toBeInTheDocument()
  expect(fallbackApiJson).toHaveBeenCalledWith(
    '/v1/data/proximity/random/availability',
    expect.objectContaining({ method: 'POST', expectedAccountId: 'recipient' }),
  )
  expect(jest.mocked(fallbackApiJson).mock.calls.some(([path]) => path.endsWith('/bottles/incoming'))).toBe(false)
})
