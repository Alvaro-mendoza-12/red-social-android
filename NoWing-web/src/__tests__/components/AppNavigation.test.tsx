import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AppNavigation } from '../../ui/novvin/AppNavigation'

jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => <span role="img" aria-label="Avatar de cuenta">A</span> }))

function show(path = '/app/social', badges: Record<string, number> = {}) {
  return render(<MemoryRouter initialEntries={[path]}><AppNavigation activeSection={path} badges={badges} avatarUrl="" displayName="Cuenta de ejemplo" /></MemoryRouter>)
}

describe('AppNavigation', () => {
  test('keeps the five primary destinations and their current-page state', () => {
    show()
    for (const [name, path] of [['Mensajes', 'messages'], ['Videos', 'videos'], ['Social', 'social'], ['Juegos', 'games'], ['Perfil', 'profile']]) {
      expect(screen.getByRole('link', { name, exact: true })).toHaveAttribute('href', `/app/${path}`)
    }
    expect(screen.getByRole('link', { name: 'Social', exact: true })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Mensajes', exact: true })).not.toHaveAttribute('aria-current')
    expect(screen.getByRole('navigation', { name: 'Navegación principal' })).toBeInTheDocument()
  })

  test('gives unread badges an accessible meaning without hiding the full count', () => {
    show('/app/messages', { '/app/messages': 24, '/app/profile': 1 })
    expect(screen.getByRole('link', { name: /Mensajes.*24 mensajes sin leer/ })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('9+')).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByRole('link', { name: /Perfil.*Actividad nueva/ })).toBeInTheDocument()
  })

  test('uses existing create route and menu event', () => {
    const onOpen = jest.fn()
    window.addEventListener('novvin:open-menu', onOpen)
    show()
    expect(screen.getByRole('link', { name: /Crear publicación/ })).toHaveAttribute('href', '/app/social?tab=posts&compose=1')
    fireEvent.click(screen.getByRole('button', { name: 'Explorar opciones' }))
    expect(onOpen).toHaveBeenCalledTimes(1)
    window.removeEventListener('novvin:open-menu', onOpen)
  })
})
