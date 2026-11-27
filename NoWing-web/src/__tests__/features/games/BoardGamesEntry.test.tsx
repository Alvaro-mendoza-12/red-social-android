import { fireEvent, render, screen } from '@testing-library/react'
import { BoardGamesEntry } from '../../../features/games/BoardGamesEntry'

beforeEach(() => localStorage.clear())

test('the favorite opens the selected board and stays private to its account on this device', () => {
  const view = render(<BoardGamesEntry key="a" accountId="a" />)
  const choice = screen.getByRole('combobox', { name: 'Mi mesa favorita' })
  expect(choice.querySelectorAll('option')).toHaveLength(8)
  fireEvent.change(choice, { target: { value: 'gomoku' } })
  expect(screen.getByRole('link', { name: 'Jugar Gomoku' })).toHaveAttribute('href', '/board-games/board-games.html?game=gomoku')

  view.rerender(<BoardGamesEntry key="b" accountId="b" />)
  expect(screen.getByRole('combobox')).toHaveValue('')
  expect(screen.getByRole('link', { name: 'Abrir juegos de mesa' })).toHaveAttribute('href', '/board-games/board-games.html')
  view.rerender(<BoardGamesEntry key="a" accountId="a" />)
  expect(screen.getByRole('combobox')).toHaveValue('gomoku')

  fireEvent.change(screen.getByRole('combobox'), { target: { value: '' } })
  expect(localStorage.getItem('novvin:board-games:favorite:a')).toBeNull()
})

test('an unrecognized favorite restores the normal board selector', () => {
  localStorage.setItem('novvin:board-games:favorite:a', 'invalid-board')
  render(<BoardGamesEntry accountId="a" />)
  expect(screen.getByRole('combobox')).toHaveValue('')
  expect(screen.getByRole('link', { name: 'Abrir juegos de mesa' })).toHaveAttribute('href', '/board-games/board-games.html')
})
