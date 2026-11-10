import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { SocialPage } from '../../features/social/SocialPage'

jest.mock('../../pages/feed/FeedPage', () => ({ FeedPage: ({ composerOpen, onCloseComposer }: { composerOpen: boolean; onCloseComposer: () => void }) => <div>Feed{composerOpen && <button onClick={onCloseComposer}>Cerrar editor de prueba</button>}</div> }))
jest.mock('../../features/moments/MomentsPage', () => ({ MomentsPage: () => <div>Contenido de Momentos</div> }))
jest.mock('../../pages/challenges/ChallengesPage', () => ({ ChallengesPage: () => <div>Contenido de Rondas</div> }))
function Location() { const location = useLocation(); return <output data-testid="route">{location.pathname}{location.search}</output> }
function show(path = '/app/social') { render(<MemoryRouter initialEntries={[path]}><SocialPage /><Location /></MemoryRouter>) }

test('introduces Social and names the existing view selector', () => {
  show()
  expect(screen.getByRole('heading', { name: 'Tu mundo, en común.', level: 2 })).toBeInTheDocument()
  const tabs = screen.getByRole('group', { name: 'Contenido de Social' })
  expect(within(tabs).getByRole('button', { name: 'Social', exact: true })).toHaveAttribute('aria-pressed', 'true')
})

test('preserves Momentos and Rondas navigation and selected state', () => {
  show()
  fireEvent.click(screen.getByRole('button', { name: 'Momentos', exact: true }))
  expect(screen.getByText('Contenido de Momentos')).toBeInTheDocument()
  expect(screen.getByTestId('route')).toHaveTextContent('/app/social?tab=moments')
  expect(screen.getByRole('button', { name: 'Momentos', exact: true })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: 'Rondas', exact: true }))
  expect(screen.getByText('Contenido de Rondas')).toBeInTheDocument()
})

test('preserves a direct editor entry and its close callback', () => {
  show('/app/social?tab=posts&compose=1')
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar editor de prueba' }))
  expect(screen.getByTestId('route')).toHaveTextContent('/app/social?tab=posts')
  expect(screen.queryByRole('button', { name: 'Cerrar editor de prueba' })).not.toBeInTheDocument()
})
