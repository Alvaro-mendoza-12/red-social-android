import { createChess, playChess, claimChessDraw, createCheckers, playCheckers,
  createLudo, rollLudo, playLudo } from './board-rules.js'

const factories = { chess: createChess, checkers: createCheckers, ludo: createLudo }
export const boardKinds = Object.keys(factories)
export function boardTurn(kind, game) {
  return kind === 'chess' ? Number(game.board.turn() === 'b') : game.turn
}
export function boardMatch(kind, seats, events) {
  if (!Object.hasOwn(factories, kind)) throw new RangeError('Juego no disponible')
  const game = factories[kind](seats)
  for (const event of events) {
    if (!applyBoardEvent(kind, game, event)) throw new Error('No se pudo reconstruir la partida')
  }
  return game
}
export function applyBoardEvent(kind, game, event) {
  if (!event || game.outcome) return false
  if (event.type === 'claim' && kind === 'chess') return claimChessDraw(game)
  if (event.type === 'move') {
    if (kind === 'chess') return playChess(game, event)
    if (kind === 'checkers') return playCheckers(game, event.from, event.to)
    if (kind === 'ludo') return playLudo(game, event.token)
  }
  if (event.type === 'roll' && kind === 'ludo') return rollLudo(game, event.roll)
  if (event.type === 'resign' && kind !== 'ludo') {
    game.outcome = { winner: 1 - boardTurn(kind, game), reason: 'Abandono' }
    return true
  }
  return false
}
