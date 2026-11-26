import { Chess } from './chess-1.4.0.js'

export function createChess() {
  const board = new Chess()
  return { board, history: [], positions: { [positionKey(board)]: 1 }, outcome: null }
}
const positionKey = board => board.fen().split(' ').slice(0, 4).join(' ')
export function chessActions(game, from) {
  if (game.outcome) return []
  return game.board.moves({ verbose: true, ...(from ? { square: from } : {}) })
}
export function playChess(game, { from, to, promotion }) {
  if (game.outcome || !/^[a-h][1-8]$/.test(from) || !/^[a-h][1-8]$/.test(to)) return false
  const selected = chessActions(game, from).find(move => move.to === to && move.promotion === promotion)
  if (!selected) return false
  game.board.move({ from, to, promotion })
  game.lastMove = [from, to]
  game.history.push(selected.san)
  const key = positionKey(game.board)
  game.positions[key] = (game.positions[key] ?? 0) + 1
  if (game.board.isCheckmate()) game.outcome = { winner: game.board.turn() === 'w' ? 1 : 0, reason: 'Jaque mate' }
  else if (game.board.isStalemate()) game.outcome = { winner: null, reason: 'Ahogado' }
  else if (game.board.isInsufficientMaterial() || game.positions[key] >= 5 || Number(game.board.fen().split(' ')[4]) >= 150) {
    game.outcome = { winner: null, reason: 'Tablas automáticas' }
  }
  return true
}
export function canClaimChessDraw(game) {
  return !game.outcome && (game.board.isThreefoldRepetition() || game.board.isDrawByFiftyMoves())
}
export function claimChessDraw(game) {
  if (!canClaimChessDraw(game)) return false
  game.outcome = { winner: null, reason: 'Tablas reclamadas' }
  return true
}

const directions = [[-1,-1],[-1,1],[1,-1],[1,1]]
function gridIndex(row, column) {
  return row >= 0 && row < 8 && column >= 0 && column < 8 ? row * 8 + column : -1
}
function checkerPosition(game) {
  return game.turn + ':' + game.board.map(piece => piece ? piece.side * 2 + (piece.king ? 2 : 1) : 0).join('')
}
export function createCheckers() {
  const board = Array.from({ length: 64 }, (_, i) => {
    const row = i >> 3
    if ((row + i % 8) % 2 === 0 || row === 3 || row === 4) return null
    return { side: row < 3 ? 1 : 0, king: false }
  })
  const game = { board, turn: 0, forced: null, quiet: 0, history: [], positions: {}, outcome: null }
  game.positions[checkerPosition(game)] = 1
  return game
}
function checkerMovesAt(game, index) {
  const piece = game.board[index]
  if (!piece || piece.side !== game.turn) return []
  const row = index >> 3, column = index % 8
  return directions.flatMap(([dy, dx]) => {
    if (!piece.king && dy !== (piece.side === 0 ? -1 : 1)) return []
    const next = gridIndex(row + dy, column + dx)
    if (next < 0) return []
    const neighbor = game.board[next]
    const landing = gridIndex(row + 2 * dy, column + 2 * dx)
    if (!neighbor) return [{ from: index, to: next, capture: null }]
    return neighbor.side !== piece.side && landing >= 0 && !game.board[landing]
      ? [{ from: index, to: landing, capture: next }] : []
  })
}
export function checkerActions(game) {
  if (game.outcome) return []
  const moves = game.forced === null
    ? game.board.flatMap((_, index) => checkerMovesAt(game, index))
    : checkerMovesAt(game, game.forced)
  const captures = moves.filter(move => move.capture !== null)
  return captures.length || game.forced !== null ? captures : moves
}
export function playCheckers(game, from, to) {
  const move = checkerActions(game).find(action => action.from === from && action.to === to)
  if (!move) return false
  const piece = game.board[from]
  const crowns = !piece.king && (piece.side === 0 ? to < 8 : to >= 56)
  game.board[from] = null
  game.board[to] = { ...piece, king: piece.king || crowns }
  if (move.capture !== null) game.board[move.capture] = null
  game.quiet = piece.king && move.capture === null ? game.quiet + 1 : 0
  const coordinate = index => String.fromCharCode(97 + index % 8) + (8 - (index >> 3))
  game.lastMove = [coordinate(from), coordinate(to)]
  game.history.push(game.lastMove.join(move.capture === null ? '–' : '×'))
  game.forced = move.capture === null || crowns ? null : to
  if (game.forced !== null && checkerActions(game).length) return true
  game.forced = null
  game.turn = 1 - game.turn
  const key = checkerPosition(game)
  game.positions[key] = (game.positions[key] ?? 0) + 1
  if (!checkerActions(game).length) game.outcome = { winner: 1 - game.turn, reason: 'Sin movimientos legales' }
  else if (game.quiet >= 80 || game.positions[key] >= 3) game.outcome = { winner: null, reason: 'Tablas' }
  return true
}

export const ludoTrack = [
  [6,1],[6,2],[6,3],[6,4],[6,5],[5,6],[4,6],[3,6],[2,6],[1,6],[0,6],[0,7],[0,8],
  [1,8],[2,8],[3,8],[4,8],[5,8],[6,9],[6,10],[6,11],[6,12],[6,13],[6,14],[7,14],[8,14],
  [8,13],[8,12],[8,11],[8,10],[8,9],[9,8],[10,8],[11,8],[12,8],[13,8],[14,8],[14,7],[14,6],
  [13,6],[12,6],[11,6],[10,6],[9,6],[8,5],[8,4],[8,3],[8,2],[8,1],[8,0],[7,0],[6,0],
]
export const ludoSafe = new Set([0,8,13,21,26,34,39,47])
export const ludoHome = [
  [[7,1],[7,2],[7,3],[7,4],[7,5],[7,6]],
  [[1,7],[2,7],[3,7],[4,7],[5,7],[6,7]],
  [[7,13],[7,12],[7,11],[7,10],[7,9],[7,8]],
  [[13,7],[12,7],[11,7],[10,7],[9,7],[8,7]],
]
const offsets = [0,13,26,39]
export const ludoColor = (game, side) => game.players === 2 && side === 1 ? 2 : side
export function createLudo(players) {
  if (![2,3,4].includes(players)) throw new RangeError('Ludo requiere de dos a cuatro jugadores.')
  return { players, turn: 0, die: null, lastDie: null, pieces: Array.from({ length: players }, () => [-1,-1,-1,-1]),
    rankings: [], history: [], outcome: null }
}
export function ludoTrackIndex(game, side, progress) {
  return progress >= 0 && progress <= 50 ? (offsets[ludoColor(game, side)] + progress) % 52 : null
}
export function ludoCell(game, side, progress) {
  const index = ludoTrackIndex(game, side, progress)
  return index === null ? ludoHome[ludoColor(game, side)][progress - 51] : ludoTrack[index]
}
export function ludoActions(game) {
  if (game.die === null || game.outcome) return []
  return game.pieces[game.turn].flatMap((progress, token) => {
    if (progress === -1) return game.die === 6 ? [{ token, to: 0 }] : []
    return progress + game.die <= 56 ? [{ token, to: progress + game.die }] : []
  })
}
function nextLudoTurn(game) {
  do { game.turn = (game.turn + 1) % game.players } while (game.rankings.includes(game.turn))
}
export function rollLudo(game, roll) {
  if (game.outcome || game.die !== null || !Number.isInteger(roll) || roll < 1 || roll > 6) return false
  game.die = roll
  game.lastDie = { side: game.turn, value: roll }
  game.history.push({ side: game.turn, roll })
  if (!ludoActions(game).length) {
    game.die = null
    if (roll !== 6) nextLudoTurn(game)
  }
  return true
}
export function playLudo(game, token) {
  const action = ludoActions(game).find(move => move.token === token)
  if (!action) return false
  const side = game.turn
  const track = ludoTrackIndex(game, side, action.to)
  let captures = 0
  if (track !== null && !ludoSafe.has(track)) game.pieces.forEach((pieces, other) => {
    if (side !== other) pieces.forEach((progress, index) => {
      if (ludoTrackIndex(game, other, progress) === track) { pieces[index] = -1; captures += 1 }
    })
  })
  game.pieces[side][token] = action.to
  game.history.push({ side, token, to: action.to, captures })
  const extraTurn = game.die === 6
  game.die = null
  if (game.pieces[side].every(progress => progress === 56)) game.rankings.push(side)
  if (game.rankings.length >= game.players - 1) {
    game.rankings.push(game.pieces.findIndex((_, player) => !game.rankings.includes(player)))
    game.outcome = { winner: game.rankings[0], reason: 'Meta alcanzada' }
  } else if (!extraTurn || game.rankings.includes(side)) nextLudoTurn(game)
  return true
}
const lineRules = {
  tic_tac_toe: { width: 3, height: 3, goal: 3, gravity: false },
  connect_four: { width: 7, height: 6, goal: 4, gravity: true },
}

export function createLineBoard(kind) {
  const rules = lineRules[kind]
  return { ...rules, board: Array(rules.width * rules.height).fill(null), turn: 0, outcome: null, history: [] }
}

export function lineBoardActions(game) {
  if (game.outcome) return []
  return game.board.flatMap((side, position) => side === null && (!game.gravity ||
    position + game.width >= game.board.length || game.board[position + game.width] !== null) ? [position] : [])
}

export function playLineBoard(game, position) {
  if (!lineBoardActions(game).includes(position)) return false
  const { width, height, goal, turn } = game
  game.board[position] = turn
  const x = position % width
  const y = Math.floor(position / width)
  const matching = (dx, dy) => {
    const positions = []
    for (let col = x + dx, row = y + dy; col >= 0 && col < width && row >= 0 && row < height;
      col += dx, row += dy) {
      const index = row * width + col
      if (game.board[index] !== turn) break
      positions.push(index)
    }
    return positions
  }
  for (const [dx, dy] of [[1, 0], [0, 1], [1, 1], [1, -1]]) {
    const line = [...matching(-dx, -dy).reverse(), position, ...matching(dx, dy)]
    if (line.length >= goal) {
      game.winningLine = line
      game.outcome = { winner: turn, reason: 'Línea completa' }
      break
    }
  }
  if (!game.outcome && !game.board.includes(null)) game.outcome = { winner: null, reason: 'Tablero completo' }
  game.history.push(`${turn === 0 ? 'X' : 'O'} · ${x + 1}, ${y + 1}`)
  game.turn = 1 - turn
  return true
}
