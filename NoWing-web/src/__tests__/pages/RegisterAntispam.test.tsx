import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { RegisterPage } from '../../pages/auth/RegisterPage'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { signUpAndBootstrapProfile } from '../../core/auth/sessionBootstrap'

jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../core/auth/fallbackAuth', () => {
  const actual = jest.requireActual('../../core/auth/fallbackAuth')
  return {
    ...actual,
    isFallbackAuthConfigured: true,
    fallbackApiJson: jest.fn(),
  }
})
jest.mock('../../core/auth/sessionBootstrap', () => ({
  signUpAndBootstrapProfile: jest.fn(),
}))

beforeEach(() => {
  jest.useFakeTimers()
  jest.clearAllMocks()
  jest.mocked(fallbackApiJson).mockResolvedValue({
    username: 'joven_novvin',
    available: true,
    suggestions: [],
  } as never)
})

afterEach(() => {
  jest.useRealTimers()
})

test('blocks registration under 13 years old and keeps outsideForm free of text', async () => {
  const { container } = render(
    <MemoryRouter initialEntries={['/register']}>
      <RegisterPage />
    </MemoryRouter>,
  )

  const outsideForm = container.cloneNode(true) as HTMLElement
  outsideForm.querySelector('form')?.remove()
  expect(outsideForm).toHaveTextContent(/^\s*$/)

  fireEvent.change(screen.getByLabelText('Nombre visible', { exact: true }), { target: { value: 'Joven Novvin' } })
  jest.advanceTimersByTime(350)
  expect(await screen.findByText(/Disponible\. Será tu identificador permanente/i)).toBeInTheDocument()

  fireEvent.change(screen.getByLabelText(/^Correo$/i), { target: { value: 'joven@novvinapp.com' } })
  fireEvent.change(screen.getByLabelText(/^Contraseña$/i), { target: { value: 'ClaveValida123' } })

  const recentYear = new Date().getUTCFullYear() - 10
  fireEvent.change(screen.getByLabelText(/Fecha de nacimiento/i), {
    target: { value: `${recentYear}-01-15` },
  })
  fireEvent.submit(screen.getByRole('button', { name: 'Crear cuenta' }).closest('form')!)

  expect(await screen.findByRole('alert')).toHaveTextContent(/al menos 13 años/i)
  expect(signUpAndBootstrapProfile).not.toHaveBeenCalled()

  fireEvent.change(screen.getByLabelText(/Fecha de nacimiento/i), {
    target: { value: '2001-03-20' },
  })
  fireEvent.submit(screen.getByRole('button', { name: 'Crear cuenta' }).closest('form')!)

  await waitFor(() => {
    expect(signUpAndBootstrapProfile).toHaveBeenCalledWith(
      null,
      expect.objectContaining({
        email: 'joven@novvinapp.com',
        birthDate: '2001-03-20',
        website: '',
      }),
    )
  })
})
