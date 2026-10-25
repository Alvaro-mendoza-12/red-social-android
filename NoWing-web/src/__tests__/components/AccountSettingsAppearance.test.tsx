import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AccountSettingsPage } from '../../features/profile/AccountSettingsPage'
import { loadFallbackProfileMe, updateFallbackProfileMe } from '../../core/fallbackData'

jest.mock('../../core/auth/AuthProvider', () => {
  const user = { id: 'synthetic-account', email: 'synthetic@example.invalid', user_metadata: {} }
  return { useAuth: () => ({ user, isFallbackSession: true }) }
})
jest.mock('../../core/fallbackData', () => ({ loadFallbackProfileMe: jest.fn(), updateFallbackProfileMe: jest.fn(), uploadFallbackFile: jest.fn() }))
jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: () => null }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))
jest.mock('../../components/QrCodeTile', () => ({ QrCodeTile: () => null }))

beforeEach(() => {
  jest.clearAllMocks()
  ;(loadFallbackProfileMe as jest.Mock).mockResolvedValue({ profile: { display_name: 'Synthetic', profile_theme: 'jade', profile_effect: 'rain', profile_welcome: 'Bienvenido' } })
  ;(updateFallbackProfileMe as jest.Mock).mockResolvedValue({ profile: { profile_theme: 'oceano', profile_effect: 'constellation', profile_welcome: 'Hola comunidad' } })
})

test('the profile customization link exposes choices and saves mobile-compatible fields', async () => {
  render(<MemoryRouter initialEntries={['/app/settings#personalizar-perfil']}><AccountSettingsPage/></MemoryRouter>)
  await screen.findByRole('region', { name: 'Personalizar perfil' })
  expect(screen.getByRole('button', { name: 'Jade', exact: true })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: 'Océano', exact: true }))
  fireEvent.click(screen.getByRole('button', { name: 'Constelación', exact: true }))
  fireEvent.change(screen.getByLabelText('Mensaje de bienvenida'), { target: { value: 'Hola comunidad' } })
  fireEvent.click(screen.getByRole('button', { name: 'Guardar personalización' }))
  await waitFor(() => expect(updateFallbackProfileMe).toHaveBeenCalledWith(expect.objectContaining({ profileTheme: 'oceano', profileEffect: 'constellation', profileWelcome: 'Hola comunidad' })))
  expect(await screen.findByText('Perfil actualizado.')).toBeInTheDocument()
})
