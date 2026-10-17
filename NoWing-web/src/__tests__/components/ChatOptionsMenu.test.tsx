import { fireEvent, render, screen } from '@testing-library/react'
import { ChatOptionsMenu } from '../../components/ChatOptionsMenu'

function setup(overrides = {}) {
  const handlers = { onPreference: jest.fn(), onInfo: jest.fn(), onSearch: jest.fn(), onSelect: jest.fn(), onAdvanced: jest.fn(), onLeave: jest.fn(), onClose: jest.fn() }
  render(<ChatOptionsMenu pinned={false} muted={false} favorite={false} archived={false} busy={false} {...handlers} {...overrides} />)
  return handlers
}
test('shows readable chat actions and saves the correct preference', () => {
  const handlers = setup()
  expect(screen.queryByText(/Herramientas de Chat|Pinear/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('menuitem', { name: 'Fijar chat' }))
  expect(handlers.onPreference).toHaveBeenCalledWith({ isPinned: true })
  expect(handlers.onClose).toHaveBeenCalledTimes(1)
})
test('keyboard moves through the menu and Escape closes it', () => {
  const handlers = setup()
  expect(screen.getByRole('menuitem', { name: 'Información del contacto' })).toHaveFocus()
  fireEvent.keyDown(screen.getByRole('menu'), { key: 'ArrowDown' })
  expect(screen.getByRole('menuitem', { name: 'Buscar mensajes' })).toHaveFocus()
  fireEvent.keyDown(screen.getByRole('menu'), { key: 'End' })
  expect(screen.getByRole('menuitem', { name: 'Cerrar chat' })).toHaveFocus()
  fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' })
  expect(handlers.onClose).toHaveBeenCalledTimes(1)
})
test('click outside closes; disabled preference cannot mutate', () => {
  const handlers = setup({ busy: true })
  expect(screen.getByRole('menuitem', { name: 'Archivar chat' })).toBeDisabled()
  fireEvent.pointerDown(document.body)
  expect(handlers.onClose).toHaveBeenCalledTimes(1)
})
