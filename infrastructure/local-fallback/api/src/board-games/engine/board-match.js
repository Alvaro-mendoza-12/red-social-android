import { createChess, playChess, claimChessDraw, createCheckers, playCheckers,
  createLudo, rollLudo, playLudo, createLineBoard, playLineBoard, createReversi, playReversi } from './board-rules.js'

const lineGames = ['tic_tac_toe', 'connect_four', 'gomoku']

const factories = { chess: createChess, checkers: createCheckers, ludo: createLudo,
  reversi: createReversi, ...Object.fromEntries(lineGames.map(kind => [kind, () => createLineBoard(kind)])) }
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
  if (event.type === 'timeout' && kind === 'chess' && event.seat === boardTurn(kind, game)) {
    game.outcome = { winner: 1 - event.seat, reason: 'Tiempo agotado' }
    return true
  }
  if (event.type === 'claim' && kind === 'chess') return claimChessDraw(game)
  if (event.type === 'move') {
    if (kind === 'chess') return playChess(game, event)
    if (kind === 'checkers') return playCheckers(game, event.from, event.to)
    if (kind === 'ludo') return playLudo(game, event.token)
    if (kind === 'reversi') return playReversi(game, event.position)
    if (lineGames.includes(kind)) return playLineBoard(game, event.position)
  }
  if (event.type === 'roll' && kind === 'ludo') return rollLudo(game, event.roll)
  if (event.type === 'resign' && kind !== 'ludo') {
    game.outcome = { winner: 1 - boardTurn(kind, game), reason: 'Abandono' }
    return true
  }
  return false
}
