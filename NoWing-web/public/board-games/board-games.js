import { createChess, chessActions, playChess, canClaimChessDraw, claimChessDraw,
  createCheckers, checkerActions, playCheckers, createLudo, rollLudo, playLudo,
  ludoActions, ludoSafe, ludoTrack, ludoHome, ludoCell, ludoColor } from './board-rules.js'
import { boardMatch, boardTurn } from './board-match.js'
import { onlineBoard } from './board-online.js'

const element = id => document.getElementById(id)
const state = { kind: 'chess', game: null, selection: null, bot: false, depth: 2, timer: 0 }
const gameNames = { chess: 'Ajedrez', checkers: 'Damas', ludo: 'Ludo' }
const colors = ['Rojo', 'Verde', 'Amarillo', 'Azul']
const symbols = { k:'♚', q:'♛', r:'♜', b:'♝', n:'♞', p:'♟' }
const pieceNames = { k:'rey', q:'dama', r:'torre', b:'alfil', n:'caballo', p:'peón' }
const values = { p: 1, n: 3, b: 3.1, r: 5, q: 9, k: 0 }
const rules = {
  chess: 'Elige una pieza y una casilla legal. Incluye enroque, captura al paso y las cuatro promociones. Puedes reclamar tablas desde la posición actual cuando corresponde.',
  checkers: 'Damas inglesas de 8 × 8. Las fichas avanzan en diagonal. La captura y la cadena son obligatorias. Una ficha coronada termina su turno.',
  ludo: 'Tira seis para salir y repite turno con seis. Las estrellas son seguras. La llegada requiere un número exacto. La clasificación se completa cuando falta un jugador por llegar.',
}
if (new URLSearchParams(location.search).get('mode') === 'android') element('back').hidden = true

function button(label, action, className = '') {
  const node = document.createElement('button')
  node.type = 'button'
  node.textContent = label
  node.className = className
  node.addEventListener('click', action)
  return node
}
function randomIndex(bound) {
  const limit = 0x100000000 - 0x100000000 % bound
  const random = new Uint32Array(1)
  do { crypto.getRandomValues(random) } while (random[0] >= limit)
  return random[0] % bound
}
const turn = () => boardTurn(state.kind, state.game)
const botTurn = () => state.bot && turn() !== 0 && !state.game.outcome
const cannotPlay = () => botTurn() || (state.room && state.room.seat !== turn())
const selection = () => ({ kind: state.kind, capacity: state.kind === 'ludo' ? Number(element('online-players').value) : 2 })
function player(side) {
  if (state.kind === 'ludo') return colors[ludoColor(state.game, side)]
  return state.kind === 'chess' ? ['Blancas','Negras'][side] : ['Claras','Rojas'][side]
}
function completeMove() {
  state.selection = null
  render()
}
function updateSetup() {
  document.querySelectorAll('[data-game]').forEach(node => node.setAttribute('aria-pressed', String(node.dataset.game === state.kind)))
  element('players-control').hidden = state.kind !== 'ludo'
  element('online-players-control').hidden = state.kind !== 'ludo'
  element('level-control').hidden = element('mode').value !== 'bot'
}
function reset() {
  clearTimeout(state.timer)
  element('promotion').close()
  state.game = null
  state.room = null
  state.selection = null
  element('table').hidden = true
  element('setup').hidden = online.mode() !== 'offline'
}
function showBoard() {
  element('setup').hidden = true
  element('table').hidden = false
  element('game-title').textContent = gameNames[state.kind]
  element('rules').textContent = rules[state.kind]
  element('restart').textContent = state.room ? 'Volver a las salas' : 'Nueva partida'
  render()
}
const online = onlineBoard({
  selection,
  clearBoard: () => reset(),
  onRoom: room => {
    state.kind = room.game
    state.bot = false
    state.room = room
    state.game = boardMatch(room.game, room.capacity, room.actions)
    state.selection = state.kind === 'checkers' ? state.game.forced : null
    updateSetup()
    showBoard()
  },
})
document.querySelectorAll('[data-game]').forEach(node => node.addEventListener('click', () => {
  reset()
  state.kind = node.dataset.game
  updateSetup()
  if (online.mode() === 'online') online.lobby()
}))
element('mode').addEventListener('change', updateSetup)
element('setup').addEventListener('submit', event => {
  event.preventDefault()
  state.bot = element('mode').value === 'bot'
  state.depth = Number(element('level').value)
  state.game = state.kind === 'chess' ? createChess() :
    state.kind === 'checkers' ? createCheckers() : createLudo(Number(element('players').value))
  showBoard()
})
element('restart').addEventListener('click', () => { if (state.room) online.lobby(); else reset() })
element('claim').addEventListener('click', () => {
  if (state.room) void online.move({ type: 'claim' })
  else if (state.kind === 'chess' && claimChessDraw(state.game)) completeMove()
})
element('resign').addEventListener('click', () => {
  if (!state.game || state.game.outcome) return
  if (state.room) {
    void online.move({ type: 'resign' })
    return
  }
  state.game.outcome = { winner: state.bot ? 1 : 1 - turn(), reason: 'Abandono' }
  completeMove()
})
element('roll').addEventListener('click', () => {
  if (state.room) void online.move({ type: 'roll' })
  else if (!botTurn() && rollLudo(state.game, randomIndex(6) + 1)) completeMove()
})
function move8(from, to, promotion) {
  if (state.room) {
    void online.move({ type: 'move', from, to, promotion })
    return
  }
  const moved = state.kind === 'chess' ? playChess(state.game, { from, to, promotion }) : playCheckers(state.game, from, to)
  if (moved) {
    state.selection = state.kind === 'chess' ? null : state.game.forced
    render()
  }
}
function moveToken(token) {
  if (state.room) void online.move({ type: 'move', token })
  else if (playLudo(state.game, token)) completeMove()
}

function board8() {
  const board = element('board')
  board.className = 'board'
  const chess = state.kind === 'chess'
  const legal = chess ? chessActions(state.game, state.selection) : checkerActions(state.game)
  const targets = legal.filter(move => move.from === state.selection)
  const squares = chess ? state.game.board.board().flat() : state.game.board
  board.replaceChildren(...squares.map((piece, index) => {
    const row = index >> 3, column = index & 7
    const coordinate = String.fromCharCode(97 + column) + (8 - row)
    const square = chess ? coordinate : index
    const destination = targets.filter(move => move.to === square)
    const selectable = Boolean(piece) && (chess ? piece.color === state.game.board.turn() : legal.some(move => move.from === square))
    const node = button('', () => {
      if (destination.length) {
        if (chess && destination.length > 1) {
          const promotion = element('promotion')
          const from = state.selection
          const current = state.game
          promotion.returnValue = ''
          promotion.addEventListener('close', () => {
            if (state.game === current && promotion.returnValue) move8(from, square, promotion.returnValue)
          }, { once: true })
          promotion.showModal()
          return
        }
        move8(state.selection, square)
        return
      }
      state.selection = state.selection === square ? null : square
      render()
    }, (row + column) % 2 ? 'dark' : 'light')
    if (destination.length) node.classList.add('legal')
    if (state.selection === square) node.classList.add('chosen')
    node.disabled = Boolean(state.game.outcome || cannotPlay() || (!selectable && !destination.length))
    let label = coordinate
    if (piece) {
      const mark = document.createElement('span')
      mark.setAttribute('aria-hidden', 'true')
      mark.className = chess ? 'piece ' + (piece.color === 'w' ? 'white' : 'black') : 'disc side-' + piece.side
      mark.textContent = chess ? symbols[piece.type] : piece.king ? '♛' : ''
      label += chess ? ', ' + pieceNames[piece.type] + (piece.color === 'w' ? ' blancas' : ' negras') :
        ', ' + (piece.king ? 'dama' : 'ficha') + (piece.side === 0 ? ' clara' : ' roja')
      node.append(mark)
    }
    node.setAttribute('aria-label', label)
    return node
  }))
}
const ludoBase = [[1,1], [1,10], [10,10], [10,1]]
function drawLudo() {
  const board = element('board')
  board.className = 'board ludo'
  const cells = Array.from({ length: 225 }, (_, index) => {
    const cell = document.createElement('div')
    const row = Math.floor(index / 15), column = index % 15
    const isBase = (row < 6 || row > 8) && (column < 6 || column > 8)
    const corner = row < 6 ? (column < 6 ? 0 : 1) : (column > 8 ? 2 : 3)
    cell.className = 'cell' + (isBase ? ' color-' + corner : '')
    return cell
  })
  for (const [index, [row, column]] of ludoTrack.entries()) {
    const cell = cells[row * 15 + column]
    cell.classList.add('track')
    if (ludoSafe.has(index)) cell.textContent = '★'
  }
  ludoHome.forEach((cellsInLane, side) => cellsInLane.forEach(([row, column]) => cells[row * 15 + column].classList.add('color-' + side)))
  const legal = new Set(ludoActions(state.game).map(move => move.token))
  for (const [side, pieces] of state.game.pieces.entries()) {
    const color = ludoColor(state.game, side)
    const [row, column] = ludoBase[color]
    for (const [token, progress] of pieces.entries()) {
      const [y, x] = progress < 0 ? [row + 2 * (token >> 1), column + 2 * (token % 2)] : ludoCell(state.game, side, progress)
      const enable = !cannotPlay() && side === turn() && legal.has(token)
      const node = button(String(token + 1), () => moveToken(token), 'token color-' + color)
      node.disabled = !enable
      node.setAttribute('aria-label', `${player(side)}, ficha ${token + 1}`)
      cells[y * 15 + x].append(node)
    }
  }
  board.replaceChildren(...cells)
  element('tokens').replaceChildren(...[...legal].map(token => {
    const node = button('Mover ficha ' + (token + 1), () => moveToken(token))
    node.disabled = Boolean(cannotPlay())
    return node
  }))
}

function botChoice(actions, evaluate) {
  if (state.depth === 0) return actions[randomIndex(actions.length)]
  const ranked = actions.map(action => ({ action, value: evaluate(action) })).sort((a,b) => b.value - a.value)
  const candidates = state.depth === 1 ? Math.ceil(ranked.length * .65) : state.depth === 2 ? Math.ceil(ranked.length * .25) : state.depth === 3 ? Math.min(2, ranked.length) : 1
  return ranked[randomIndex(candidates)].action
}
function runBot() {
  if (!botTurn()) return
  if (state.kind === 'chess') {
    const move = botChoice(chessActions(state.game), candidate => {
      const value = (values[candidate.captured] || 0) * 2 + (values[candidate.promotion] || 0) + (candidate.san.includes('#') ? 100 : candidate.san.includes('+') ? .4 : 0)
      if (state.depth < 3) return value
      const copy = createChess().board
      copy.load(candidate.after)
      const threat = copy.moves({ verbose: true }).reduce((highest, reply) => Math.max(highest, (values[reply.captured] || 0) + (reply.san.includes('#') ? 100 : 0)), 0)
      return value - threat
    })
    playChess(state.game, move)
  } else if (state.kind === 'checkers') {
    const move = botChoice(checkerActions(state.game), candidate => {
      const copy = structuredClone(state.game)
      playCheckers(copy, candidate.from, candidate.to)
      const losesPiece = state.depth >= 3 && copy.turn !== state.game.turn && checkerActions(copy).some(reply => reply.capture === candidate.to)
      return (candidate.capture === null ? 0 : 3) + Number(copy.board[candidate.to].king) - Number(losesPiece) * 2
    })
    playCheckers(state.game, move.from, move.to)
  } else if (state.game.die === null) rollLudo(state.game, randomIndex(6) + 1)
  else {
    const move = botChoice(ludoActions(state.game), candidate => candidate.to === 56 ? 100 :
      state.game.pieces[turn()][candidate.token] < 0 ? 40 : candidate.to)
    playLudo(state.game, move.token)
  }
  state.selection = state.kind === 'checkers' ? state.game.forced : null
  render()
}

function render() {
  clearTimeout(state.timer)
  const game = state.game
  const result = game.outcome
  const lastRoll = game.lastDie ? ` · ${player(game.lastDie.side)} sacó ${game.lastDie.value}` : ''
  const onlineTurn = state.room ? (state.room.seat === turn() ? ' · Tu turno' : ' · Esperando jugada') : ''
  element('status').textContent = result ?
    `${result.winner === null ? 'Empate' : 'Gana ' + player(result.winner)} · ${result.reason}` :
    `Turno: ${player(turn())}${botTurn() ? ' · Novvin' : onlineTurn}${state.kind === 'chess' && game.board.isCheck() ? ' · Jaque' : ''}${lastRoll}`
  const ludo = state.kind === 'ludo'
  element('roll').hidden = !ludo || Boolean(result)
  element('roll').disabled = Boolean(cannotPlay()) || game.die !== null
  element('claim').hidden = state.kind !== 'chess' || !canClaimChessDraw(game) || Boolean(cannotPlay())
  element('resign').hidden = ludo || Boolean(result) || Boolean(cannotPlay())
  element('tokens').replaceChildren()
  if (ludo) drawLudo()
  else board8()
  const log = game.history.slice(-80).map(move => {
    const item = document.createElement('li')
    item.textContent = typeof move === 'string' ? move : 'roll' in move ? `${player(move.side)} sacó ${move.roll}` :
      `${player(move.side)} movió la ficha ${move.token + 1}${move.captures ? ' y capturó' : ''}`
    return item
  })
  if (ludo) game.rankings.forEach((side, rank) => {
    const item = document.createElement('li')
    item.textContent = `${rank + 1}. ${player(side)}`
    log.push(item)
  })
  element('moves').replaceChildren(...log)
  if (botTurn()) state.timer = window.setTimeout(runBot, 380)
}
updateSetup()
