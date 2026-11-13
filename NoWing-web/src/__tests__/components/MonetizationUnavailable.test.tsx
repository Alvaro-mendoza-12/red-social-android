import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MonetizationCenterPage } from '../../features/profile/MonetizationCenterPage'

jest.mock('../../core/auth/AuthProvider', () => ({
  useAuth: () => ({ user: { id: 'synthetic' }, isFallbackSession: false }),
}))
jest.mock('../../core/auth/fallbackAuth', () => ({
  isFallbackAuthConfigured: false,
  fallbackApiJson: jest.fn(),
}))
jest.mock('../../core/supabase', () => ({ supabase: null, reportSupabaseServiceRestrictionIfPresent: jest.fn() }))

test('unavailable monetization explains the limitation and provides a working exit', () => {
  render(<MemoryRouter><MonetizationCenterPage /></MemoryRouter>)
  expect(screen.getByRole('heading', { name: 'Monetización no disponible' })).toBeVisible()
  expect(screen.getByText(/Inicia sesión con el servidor de producción/)).toBeVisible()
  expect(screen.getByRole('link', { name: 'Volver a mi perfil' })).toHaveAttribute('href', '/app/profile')
  expect(screen.getByRole('link', { name: 'Consultar ayuda' })).toHaveAttribute('href', '/app/help')
  expect(screen.queryByText(/CSV|VM|legacy|VITE_/)).not.toBeInTheDocument()
})
