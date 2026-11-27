import { useState } from 'react'
import { ArrowRight } from 'lucide-react'
import './boardGamesEntry.css'

const boards = [
  ['chess', 'Ajedrez'],
  ['ludo', 'Ludo'],
  ['checkers', 'Damas'],
  ['tic_tac_toe', 'Tres en raya'],
  ['connect_four', 'Cuatro en línea'],
  ['reversi', 'Reversi'],
  ['gomoku', 'Gomoku'],
] as const

function readFavorite(storageKey: string) {
  try {
    const stored = localStorage.getItem(storageKey)
    return boards.find(([id]) => id === stored)?.[0] ?? ''
  } catch {
    return ''
  }
}

export function BoardGamesEntry({ accountId }: { accountId: string }) {
  const storageKey = 'novvin:board-games:favorite:' + encodeURIComponent(accountId)
  const [favorite, setFavorite] = useState<string>(() => readFavorite(storageKey))
  const selected = boards.find(([id]) => id === favorite)
  const url = '/board-games/board-games.html' + (selected ? '?game=' + selected[0] : '')

  function chooseFavorite(value: string) {
    const next = boards.find(([id]) => id === value)?.[0] ?? ''
    setFavorite(next)
    try {
      if (next) localStorage.setItem(storageKey, next)
      else localStorage.removeItem(storageKey)
    } catch { /* The chosen board remains available for this visit. */ }
  }

  return (
    <section className="board-entry" aria-labelledby="board-entry-title">
      <div className="board-entry-intro">
        <div>
          <h2 id="board-entry-title">Juegos de mesa</h2>
          <p>Juega con amigos, contra Novvin o en este dispositivo.</p>
        </div>
        <div className="board-entry-art" data-board={selected?.[0] ?? 'chess'} aria-hidden="true" />
      </div>
      <div className="board-entry-actions">
        <label>
          Mi mesa favorita
          <select className="text-input" value={favorite} onChange={event => chooseFavorite(event.target.value)}>
            <option value="">Sin favorito</option>
            {boards.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          </select>
        </label>
        <a className="primary-btn" href={url}>
          {selected ? 'Jugar ' + selected[1] : 'Abrir juegos de mesa'}
          <ArrowRight size={17} aria-hidden="true" />
        </a>
      </div>
    </section>
  )
}
