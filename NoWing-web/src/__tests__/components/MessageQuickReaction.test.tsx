import { fireEvent, render, screen } from '@testing-library/react'
import { MessageOptionsMenu } from '../../components/MessageOptionsMenu'

test('a separate reaction shortcut keeps the three-dot menu and closes after choosing', () => {
  const onReact = jest.fn()
  render(<MessageOptionsMenu mine onReact={onReact}><button>Responder</button></MessageOptionsMenu>)
  expect(screen.getByLabelText('Opciones del mensaje')).toBeInTheDocument()
  const shortcut = screen.getByLabelText('Reaccionar al mensaje')
  fireEvent.click(shortcut)
  fireEvent.click(screen.getByRole('button', { name: 'Reaccionar con ❤️' }))
  expect(onReact).toHaveBeenCalledWith('❤️')
  expect(shortcut.closest('details')).not.toHaveAttribute('open')
})
