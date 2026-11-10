import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AccountSettingsPage } from '../../features/profile/AccountSettingsPage'
import { changeFallbackPassword } from '../../core/auth/fallbackAuth'

jest.mock('../../core/auth/AuthProvider', () => {
  const user = { id: 'synthetic-account', email: 'synthetic@example.invalid', user_metadata: {} }
  return { useAuth: () => ({ user, isFallbackSession: true }) }
})
jest.mock('../../core/auth/fallbackAuth', () => ({ changeFallbackPassword: jest.fn(), updateFallbackSessionProfile: jest.fn() }))
jest.mock('../../core/fallbackData', () => ({ loadFallbackProfileMe: async () => ({ profile: { display_name: 'Synthetic' } }), updateFallbackProfileMe: jest.fn(), uploadFallbackFile: jest.fn() }))
jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: () => null }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))
jest.mock('../../components/QrCodeTile', () => ({ QrCodeTile: () => null }))

beforeEach(() => { jest.clearAllMocks(); (changeFallbackPassword as jest.Mock).mockResolvedValue(undefined) })

async function fillPassword(current: string, next: string, confirmation: string) {
  render(<MemoryRouter><AccountSettingsPage /></MemoryRouter>)
  await screen.findByLabelText('Contraseña actual')
  fireEvent.change(screen.getByLabelText('Contraseña actual'), { target: { value: current } })
  fireEvent.change(screen.getByLabelText('Nueva contraseña'), { target: { value: next } })
  fireEvent.change(screen.getByLabelText('Confirmar nueva contraseña'), { target: { value: confirmation } })
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Cambiar contraseña' })) })
}

test('sends both passwords exactly as typed, including surrounding spaces', async () => {
  await fillPassword(' old password ', ' new password ', ' new password ')
  await screen.findByText('Contraseña actualizada.')
  expect(changeFallbackPassword).toHaveBeenCalledWith(' old password ', ' new password ')
})

test('a confirmation differing only by whitespace is rejected', async () => {
  await fillPassword('old password', ' new password ', 'new password')
  await waitFor(() => expect(screen.getByText('La confirmación no coincide con la contraseña nueva.')).toBeVisible())
  expect(changeFallbackPassword).not.toHaveBeenCalled()
})
