import { fireEvent, render, screen } from '@testing-library/react'
import { RoomReactionMenu } from '../../components/RoomReactionMenu'

test('reactions occupy one button and close after selection, outside click or Escape', () => {
  const react = jest.fn()
  render(<RoomReactionMenu onReaction={react}/>)
  const trigger = screen.getByRole('button', { name: 'Reacciones de la sala' })
  expect(screen.queryByRole('group')).not.toBeInTheDocument()
  fireEvent.click(trigger); expect(trigger).toHaveAttribute('aria-expanded','true')
  fireEvent.click(screen.getByRole('button',{name:'Reaccionar ❤️'})); expect(react).toHaveBeenCalledWith('❤️')
  expect(screen.queryByRole('group')).not.toBeInTheDocument()
  fireEvent.click(trigger); fireEvent.keyDown(document,{key:'Escape'}); expect(trigger).toHaveFocus()
  fireEvent.click(trigger); fireEvent.pointerDown(document.body); expect(trigger).toHaveAttribute('aria-expanded','false')
})
