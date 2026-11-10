import { render, screen, within } from '@testing-library/react'
import { PublicHome } from '../../ui/novvin/PublicHome'

describe('PublicHome visual entry points', () => {
  test('explains the product with an explicitly labeled example rather than live activity', () => {
    render(<PublicHome />)
    const example = screen.getByRole('figure', { name: 'Una conversación en Novvin, con datos de ejemplo' })
    expect(within(example).getByText('Vista ilustrativa · datos de ejemplo')).toBeVisible()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  test('preserves direct account, guest and Android entry points', () => {
    render(<PublicHome />)
    expect(screen.getByRole('link', { name: 'Entrar a Novvin' })).toHaveAttribute('href', '/login')
    expect(screen.getByRole('link', { name: 'Explorar videos' })).toHaveAttribute('href', '/explore/videos')
    expect(screen.getByRole('link', { name: /¿Eres nuevo/ })).toHaveAttribute('href', '/register')
    expect(screen.getByRole('link', { name: /También disponible para Android/ })).toHaveAttribute('href', '/descargar')
    expect(screen.getByRole('link', { name: 'Ir al contenido' })).toHaveAttribute('href', '#contenido')
  })

  test('provides three named choices without adding new product routes', () => {
    render(<PublicHome />)
    const choices = screen.getByRole('region', { name: 'Elige cómo conectar' })
    expect(within(choices).getAllByRole('article')).toHaveLength(3)
    expect(within(choices).getByRole('link', { name: /Abrir mis mensajes/ })).toHaveAttribute('href', '/login?next=%2Fapp%2Fmessages')
    expect(within(choices).getByRole('link', { name: /Descubrir Social/ })).toHaveAttribute('href', '/explore/posts')
    expect(within(choices).getByRole('link', { name: /Ver momentos/ })).toHaveAttribute('href', '/explore/moments')
  })
})
