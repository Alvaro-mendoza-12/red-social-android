import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { RegisterPage } from '../../pages/auth/RegisterPage'
import { signUpAndBootstrapProfile } from '../../core/auth/sessionBootstrap'

jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../core/auth/sessionBootstrap', () => ({ signUpAndBootstrapProfile: jest.fn(async () => {}) }))
jest.mock('../../core/webPreferences', () => ({ useWebPreferences: () => [{ accentTheme: 'purple' }] }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: ({ src, alt }: { src: string; alt: string }) => <img src={src} alt={alt}/> }))
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true,
  fallbackApiJson: jest.fn(async (path: string) => ({ username: new URL(path, 'https://example.invalid').searchParams.get('username'), available: true, suggestions: [] })),
}))

test('confirmed registration returns to the invited room instead of the inbox', async () => {
  render(<MemoryRouter initialEntries={['/register?next=%2Fapp%2Fvoice%2Froom-123%3Fjoin%3D1']}><Routes>
    <Route path="/register" element={<RegisterPage/>}/>
    <Route path="/app/voice/:roomId" element={<p>Invited room</p>}/>
  </Routes></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('Nombre visible'), { target: { value: 'QA Room' } })
  fireEvent.change(screen.getByLabelText(/Identificador único/), { target: { value: 'qa_room' } })
  fireEvent.change(screen.getByLabelText('Correo'), { target: { value: 'qa@example.invalid' } })
  fireEvent.change(screen.getByLabelText('Contraseña'), { target: { value: 'SyntheticPassword123' } })
  await waitFor(() => expect(screen.getByRole('button', { name: 'Crear cuenta' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Crear cuenta' }))
  expect(await screen.findByText('Invited room')).toBeInTheDocument()
  expect(signUpAndBootstrapProfile).toHaveBeenCalledTimes(1)
})
