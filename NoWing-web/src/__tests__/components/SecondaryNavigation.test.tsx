import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SecondaryNavigation } from '../../ui/novvin/SecondaryNavigation'

function show(onNavigate = jest.fn()) {
  return render(<MemoryRouter><SecondaryNavigation onNavigate={onNavigate} /></MemoryRouter>)
}

describe('Explore navigation', () => {
  test.each([
    ['Asistente de Novvin', 'assistant', 'bot'],
    ['Buscar', 'search', 'search'],
    ['Notificaciones', 'notifications', 'bell'],
    ['Llamadas', 'calls', 'phone'],
    ['Salas de voz', 'voice', 'mic'],
    ['Crear espacio', 'create-space', 'circle-plus'],
    ['En vivo', 'live', 'radio'],
    ['Personas cerca', 'proximity', 'map-pin'],
    ['Ajustes', 'settings', 'settings'],
    ['Mis reportes', 'reports', 'flag'],
    ['Ayuda', 'help', 'circle-help'],
  ])('pairs %s with its destination and recognizable symbol', (name, route, icon) => {
    show()
    const link = screen.getByRole('link', { name, exact: true })
    expect(link).toHaveAttribute('href', `/app/${route}`)
    expect(link.querySelector('svg')).toHaveClass(`lucide-${icon}`)
    expect(link.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  test('dismisses the menu when choosing a destination', () => {
    const onNavigate = jest.fn()
    show(onNavigate)
    fireEvent.click(screen.getByRole('link', { name: 'Ayuda', exact: true }))
    expect(onNavigate).toHaveBeenCalledTimes(1)
  })
})
