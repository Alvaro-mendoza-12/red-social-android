import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ErrorGalleryPage } from '../../features/errors/ErrorGalleryPage'
import { errorCatalog, offlineProblem, problemForError, problemForStatus } from '../../features/errors/errorCatalog'
import { PersonifiedErrorPanel } from '../../features/errors/PersonifiedErrorPanel'
import { NotFoundPage } from '../../ui/novvin/NotFoundPage'

class Utterance {
  lang = ''
  rate = 1
  pitch = 1
  onend: (() => void) | null = null
  constructor(readonly text: string) {}
}

const speak = jest.fn<void, [Utterance]>()
const cancel = jest.fn()
const voice = { name: 'Español', lang: 'es-MX', localService: true }
const getVoices = jest.fn(() => [voice])

beforeEach(() => {
  Object.defineProperty(window, 'SpeechSynthesisUtterance', { configurable: true, value: Utterance })
  Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: { speak, cancel, getVoices } })
})

afterEach(() => {
  speak.mockClear()
  cancel.mockClear()
  getVoices.mockClear()
  Reflect.deleteProperty(window, 'SpeechSynthesisUtterance')
  Reflect.deleteProperty(window, 'speechSynthesis')
})

test.each(errorCatalog)('HTTP $code has a safe card, Spanish narration and working stop control', (entry) => {
  const error = Object.assign(new Error('internal database at https://private.invalid'), { status: entry.code })
  const problem = problemForError({ cause: error })
  expect(problem).toBe(entry)
  render(<PersonifiedErrorPanel problem={entry} />)
  expect(screen.getByRole('heading', { name: entry.title })).toBeVisible()
  expect(screen.queryByText(/private.invalid/)).toBeNull()
  expect(speak).not.toHaveBeenCalled()
  expect(document.querySelector(`img[src="/error-characters/${entry.character}.webp"]`)).not.toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Escuchar' }))
  const utterance = speak.mock.calls[0][0]
  expect(utterance.lang).toBe('es-MX')
  expect(utterance.text).toBe(`Error ${entry.spokenCode}. ${entry.title}. ${entry.description}`)
  expect(screen.getByRole('button', { name: 'Detener' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: 'Detener' }))
  expect(cancel).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button', { name: 'Escuchar' })).toBeEnabled()
})

test('selects another character, stops the previous speech and preserves navigation', () => {
  render(<MemoryRouter><ErrorGalleryPage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: 'Escuchar' }))
  fireEvent.click(within(screen.getByRole('region', { name: 'Conoce a los personajes' })).getByRole('button', { name: /500/ }))
  expect(cancel).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('heading', { name: 'Algo salió mal' })).toBeVisible()
  expect(screen.getByRole('link', { name: 'Centro de ayuda' })).toHaveAttribute('href', '/ayuda')
  expect(screen.getByRole('link', { name: 'NOVVIN, inicio' })).toHaveAttribute('href', '/')
  expect(screen.getByRole('region', { name: 'Códigos de error' }).querySelectorAll('button')).toHaveLength(12)
})

test('shows a readable alternative when Spanish voices are unavailable', () => {
  getVoices.mockReturnValueOnce([{ ...voice, lang: 'en-US' }])
  render(<PersonifiedErrorPanel problem={offlineProblem} />)
  fireEvent.click(screen.getByRole('button', { name: 'Escuchar' }))
  expect(speak).not.toHaveBeenCalled()
  expect(screen.getByText(/La voz en español no está disponible/)).toBeVisible()
  expect(screen.getByRole('heading', { name: offlineProblem.title })).toBeVisible()
})

test('404 keeps its recovery links and unknown statuses remain unclassified', () => {
  render(<NotFoundPage />)
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(problemForStatus(404)!.title)
  expect(screen.getByRole('link', { name: 'Abrir mensajes' })).toHaveAttribute('href', '/app/messages')
  expect(screen.getByRole('link', { name: 'Errores con personalidad' })).toHaveAttribute('href', '/ayuda/errores')
  expect(problemForError({ statusCode: 418 })).toBeUndefined()
  expect(problemForError(Object.assign(new Error(), { name: 'AbortError' }))).toBeUndefined()
})
