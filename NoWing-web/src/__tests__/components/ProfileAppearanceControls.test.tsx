import { fireEvent, render, screen } from '@testing-library/react'
import { ProfileAppearanceControls } from '../../components/ProfileAppearanceControls'

test('offers the persisted mobile theme and effect identifiers with visible selection', () => {
  const theme = jest.fn(), effect = jest.fn()
  const { rerender } = render(<ProfileAppearanceControls theme="jade" effect="rain" onTheme={theme} onEffect={effect}/>)
  expect(screen.getByRole('button', { name: 'Jade' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: 'Lluvia' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: 'Océano' }))
  fireEvent.click(screen.getByRole('button', { name: 'Constelación' }))
  expect(theme).toHaveBeenCalledWith('oceano'); expect(effect).toHaveBeenCalledWith('constellation')
  rerender(<ProfileAppearanceControls theme="oceano" effect="constellation" onTheme={theme} onEffect={effect}/>)
  expect(screen.getByRole('button', { name: 'Océano' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: 'Jade' })).toHaveAttribute('aria-pressed', 'false')
  expect(screen.getByRole('group', { name: 'Color del perfil' }).querySelectorAll('button')).toHaveLength(24)
})
