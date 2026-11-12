import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MonetizationCenterPage } from '../../features/profile/MonetizationCenterPage'
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'synthetic' } }) }))
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true }))
jest.mock('../../core/supabase', () => ({ supabase: null }))

test('unavailable monetization explains the limitation and provides a working exit', () => {
  render(<MemoryRouter><MonetizationCenterPage /></MemoryRouter>)
  expect(screen.getByRole('heading', { name: 'Monetización no disponible' })).toBeVisible()
  expect(screen.getByText(/solicitar retiros desde la web/)).toBeVisible()
  expect(screen.getByRole('link', { name: 'Volver a mi perfil' })).toHaveAttribute('href', '/app/profile')
  expect(screen.getByRole('link', { name: 'Consultar ayuda' })).toHaveAttribute('href', '/app/help')
  expect(screen.queryByText(/CSV|VM|legacy|VITE_/)).not.toBeInTheDocument()
})
