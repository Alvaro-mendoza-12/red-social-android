import { render, screen } from '@testing-library/react'
import { AssistantAnswer } from '../../components/AssistantAnswer'
test('organizes headings, steps and code while leaving HTML and unsafe URLs inert', () => {
  const { container } = render(<AssistantAnswer text={'## Publicar\n\n1. Abre **Social**\n2. Elige `Foto`\n\n```html\n<img src=x onerror=alert(1)>\n```\n\n[enlace](javascript:alert(1))'}/>)
  expect(screen.getByRole('heading', { name: 'Publicar' })).toBeInTheDocument()
  expect(screen.getAllByRole('listitem')).toHaveLength(2)
  expect(screen.getByText('Social').tagName).toBe('STRONG')
  expect(screen.getByLabelText('Código de la respuesta')).toHaveTextContent('<img src=x onerror=alert(1)>')
  expect(container.querySelector('img, script, a')).toBeNull()
})
test('an unfinished code block remains readable', () => {
  render(<AssistantAnswer text={'Respuesta\n\n```\nconst answer = 42'}/>)
  expect(screen.getByLabelText('Código de la respuesta')).toHaveTextContent('const answer = 42')
})
