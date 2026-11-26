import { createChess, chessActions, playChess, canClaimChessDraw, claimChessDraw,
  checkerActions, playCheckers, rollLudo, playLudo,
  ludoActions, ludoSafe, ludoTrack, ludoHome, ludoCell, ludoColor, lineBoardActions, playLineBoard,
  reversiActions, playReversi } from './board-rules.js'
import { boardMatch, boardTurn } from './board-match.js'
import { boardNames, onlineBoard } from './board-online.js'

const element = id => document.getElementById(id)
const state = { kind: 'chess', game: null, selection: null, bot: false, depth: 2, timer: 0 }
const gameDetails = {
  chess: ['Estrategia', '2 jugadores', 'Domina el centro, protege tu rey y encuentra el jaque mate.'],
  ludo: ['Para amigos', '2 a 4 jugadores', 'Lanza el dado y lleva tus cuatro fichas a la meta.'],
  checkers: ['Estrategia', '2 jugadores', 'Encadena capturas y corona tus fichas en el otro extremo.'],
  tic_tac_toe: ['Rápidos', '2 jugadores', 'Conecta tres marcas antes de que se llene el tablero.'],
  connect_four: ['Rápidos', '2 jugadores', 'Deja caer tus fichas y alinea cuatro en cualquier dirección.'],
  reversi: ['Estrategia', '2 jugadores', 'Rodea al rival y voltea sus discos para dominar el tablero.'],
  gomoku: ['Estrategia', '2 jugadores', 'Coloca cinco piedras seguidas sobre las líneas del tablero.'],
}
let listedRooms
const colors = ['Rojo', 'Verde', 'Amarillo', 'Azul']
const symbols = { k:'♚', q:'♛', r:'♜', b:'♝', n:'♞', p:'♟' }
const pieceNames = { k:'rey', q:'dama', r:'torre', b:'alfil', n:'caballo', p:'peón' }
const values = { p: 1, n: 3, b: 3.1, r: 5, q: 9, k: 0 }
const rules = {
  chess: 'Elige una pieza y una casilla legal. Incluye enroque, captura al paso y las cuatro promociones. Puedes reclamar tablas desde la posición actual cuando corresponde.',
  checkers: 'Damas inglesas de 8 × 8. Las fichas avanzan en diagonal. La captura y la cadena son obligatorias. Una ficha coronada termina su turno.',
  ludo: 'Tira seis para salir y repite turno con seis. Las estrellas son seguras. La llegada requiere un número exacto. La clasificación se completa cuando falta un jugador por llegar.',
  tic_tac_toe: 'Coloca una marca en cada turno y forma una línea de tres en cualquier dirección.',
  connect_four: 'Elige una columna para dejar caer una ficha. Gana quien alinee cuatro fichas en horizontal, vertical o diagonal.',
  reversi: 'Coloca un disco donde puedas encerrar y voltear al menos un disco rival. Si no tienes jugadas, pasas turno. Gana quien tenga más discos cuando nadie pueda seguir.',
  gomoku: 'Coloca una piedra en una intersección vacía. Gana quien alinee cinco o más piedras en horizontal, vertical o diagonal.',
}
if (new URLSearchParams(location.search).get('mode') === 'android') document.querySelector('.portal-header').hidden = true

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
const selection = () => ({ kind: state.kind, capacity: state.kind === 'ludo' ? Number(element('online-players').value) : 2,
  clockMinutes: state.kind === 'chess' ? Number(element('online-clock').value) : 0 })
const isLineGame = () => ['tic_tac_toe', 'connect_four', 'gomoku', 'reversi'].includes(state.kind)
function player(side) {
  if (state.kind === 'ludo') return colors[ludoColor(state.game, side)]
  if (state.kind === 'gomoku' || state.kind === 'reversi') return ['Negras', 'Blancas'][side]
  if (isLineGame()) return ['X', 'O'][side]
  return state.kind === 'chess' ? ['Blancas','Negras'][side] : ['Claras','Rojas'][side]
}
function completeMove() {
  state.selection = null
  render()
}
function updateSetup() {
  const [category, seats, description] = gameDetails[state.kind]
  element('featured').dataset.cover = state.kind
  element('featured-meta').textContent = `${category} · ${seats}`
  element('featured-title').textContent = boardNames[state.kind]
  element('featured-description').textContent = description
  updateAvailability()
  document.querySelectorAll('#games [data-game]').forEach(node => node.setAttribute('aria-pressed', String(node.dataset.game === state.kind)))
  element('players-control').hidden = state.kind !== 'ludo'
  element('online-players-control').hidden = state.kind !== 'ludo'
  element('level-control').hidden = element('mode').value !== 'bot'
  element('clock-control').hidden = state.kind !== 'chess'
  element('online-clock-control').hidden = state.kind !== 'chess'
}

function updateAvailability() {
  const visible = listedRooms?.filter(room => room.game === state.kind) ?? []
  const active = visible.filter(room => room.seat >= 0 && room.status === 'playing').length
  const waiting = visible.filter(room => room.status === 'waiting').length
  const count = active ? `${active} ${active === 1 ? 'partida tuya en curso' : 'partidas tuyas en curso'}` : ''
  element('featured-availability').textContent = [count, waiting ? `${waiting} ${waiting === 1 ? 'mesa en espera' : 'mesas en espera'}` : ''].filter(Boolean).join(' · ')
}
function reset() {
  clearTimeout(state.timer)
  clearInterval(state.chessTimer)
  state.chessClock = null
  state.lastRendered = null
  element('promotion').close()
  if (element('abandon-dialog').open) element('abandon-dialog').close()
  state.game = null
  state.room = null
  state.rosterIdentity = null
  state.selection = null
  element('table').hidden = true
  element('hub').hidden = false
  element('catalog').hidden = false
  element('setup').hidden = online.mode() !== 'offline'
}
function showBoard() {
  const opening = element('table').hidden
  element('hub').hidden = true
  element('catalog').hidden = true
  element('setup').hidden = true
  element('table').hidden = false
  element('table').dataset.game = state.kind
  element('game-title').textContent = boardNames[state.kind]
  element('rules').textContent = rules[state.kind]
  element('restart').hidden = Boolean(state.room)
  render()
  if (opening) {
    window.scrollTo(0, 0)
    element('game-title').focus({ preventScroll: true })
  }
}
const online = onlineBoard({
  selection,
  clearBoard: () => reset(),
  onRooms: rooms => { listedRooms = rooms; updateAvailability() },
  onRoom: room => {
    state.kind = room.game
    state.bot = false
    state.room = room
    state.game = boardMatch(room.game, room.capacity, room.actions)
    clearInterval(state.chessTimer)
    state.chessClock = null
    state.roomClockReceivedAt = performance.now()
    if (room.clock?.startedAt != null) state.chessTimer = setInterval(tickChessClock, 200)
    state.selection = state.kind === 'checkers' ? state.game.forced : null
    updateSetup()
    showBoard()
  },
})
const gameCards = [...document.querySelectorAll('#games [data-game]')]
document.querySelectorAll('#game-filters [data-filter]').forEach(control => control.addEventListener('click', () => {
  const filter = control.dataset.filter
  for (const card of gameCards) card.hidden = filter !== 'all' && card.dataset.category !== filter
  document.querySelectorAll('#game-filters [data-filter]').forEach(item => item.setAttribute('aria-pressed', String(item === control)))
}))
document.querySelectorAll('#games [data-game]').forEach(node => node.addEventListener('click', () => {
  reset()
  state.kind = node.dataset.game
  updateSetup()
  if (online.mode() === 'online') online.lobby()
  element('featured-title').focus({ preventScroll: true })
  element('featured').scrollIntoView?.({ block: 'nearest' })
}))
element('mode').addEventListener('change', updateSetup)
element('setup').addEventListener('submit', event => {
  event.preventDefault()
  state.bot = element('mode').value === 'bot'
  state.depth = Number(element('level').value)
  startLocal()
})
element('restart').addEventListener('click', startLocal)
function startLocal() {
  state.game = boardMatch(state.kind, Number(element('players').value), [])
  state.selection = null
  state.lastRendered = null
  clearInterval(state.chessTimer)
  const length = state.kind === 'chess' ? Number(element('chess-time').value) * 60000 : 0
  state.chessClock = length ? { remaining: [length, length], active: 0, startedAt: performance.now() } : null
  if (state.chessClock) state.chessTimer = setInterval(tickChessClock, 200)
  showBoard()
}

function tickChessClock() {
  const clock = state.chessClock
  if (state.room?.clock) {
    const remote = state.room.clock
    const remaining = [...remote.remaining]
    if (remote.startedAt != null) {
      const elapsed = Math.max(0, remote.serverNow - remote.startedAt + performance.now() - state.roomClockReceivedAt)
      remaining[remote.seat] = Math.max(0, remaining[remote.seat] - elapsed)
    }
    drawClock(remaining, remote.startedAt != null ? remote.seat : -1)
    return true
  }
  element('chess-clock').hidden = !clock
  if (!clock) return true
  const now = performance.now()
  if (!state.game.outcome) {
    clock.remaining[clock.active] = Math.max(0, clock.remaining[clock.active] - (now - clock.startedAt))
    clock.active = turn()
    clock.startedAt = now
  } else clearInterval(state.chessTimer)
  drawClock(clock.remaining, state.game.outcome ? -1 : clock.active)
  if (!state.game.outcome && clock.remaining.includes(0)) {
    state.game.outcome = { winner: clock.remaining[0] === 0 ? 1 : 0, reason: 'Tiempo agotado' }
    render()
    return false
  }
  return !state.game.outcome
}
function drawClock(times, active) {
  element('chess-clock').hidden = false
  times.forEach((remaining, seat) => {
    const seconds = Math.ceil(remaining / 1000)
    const slot = element(seat ? 'black-time' : 'white-time')
    const label = `${seat ? 'Negras' : 'Blancas'} ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
    if (slot.textContent !== label) slot.textContent = label
    const running = String(seat === active)
    if (slot.dataset.running !== running) slot.dataset.running = running
  })
}
element('return-to-lobby').addEventListener('click', () => { if (state.room) online.lobby(); else reset() })
element('claim').addEventListener('click', () => {
  if (state.room) void online.move({ type: 'claim' })
  else if (state.kind === 'chess' && claimChessDraw(state.game)) completeMove()
})
element('resign').addEventListener('click', () => {
  if (!state.game || state.game.outcome) return
  const dialog = element('abandon-dialog')
  dialog.returnValue = ''
  dialog.showModal()
})
element('abandon-dialog').addEventListener('close', event => {
  if (event.currentTarget.returnValue !== 'abandon' || !state.game || state.game.outcome || cannotPlay()) return
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
  if (state.kind === 'chess' && !tickChessClock()) return
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

function playLineAt(position) {
  if (state.room) void online.move({ type: 'move', position })
  else if ((state.kind === 'reversi' ? playReversi : playLineBoard)(state.game, position)) completeMove()
}

function renderRoster(game) {
  const identity = state.room || game
  const activeSeat = game.outcome ? null : turn()
  if (state.rosterIdentity === identity && state.rosterTurn === activeSeat) return
  state.rosterIdentity = identity
  state.rosterTurn = activeSeat
  const local = {
    seat: state.bot ? 0 : -1,
    players: Array.from({ length: game.players ?? 2 }, (_, seat) => ({
      seat,
      name: state.bot ? (seat === 0 ? undefined : 'Novvin') : `Jugador ${seat + 1}`,
    })),
  }
  online.renderPlayers(state.room || local, activeSeat, player,
    state.kind === 'ludo' ? seat => ludoColor(game, seat) : undefined)
}

function drawColumns(grid, game, actions) {
  const activeColumn = grid.contains(document.activeElement) ? document.activeElement.dataset.column : undefined
  const landingByColumn = new Map(actions.map(position => [position % game.width, position]))
  const playable = !cannotPlay()
  const columns = Array.from({ length: game.width }, (_, column) => {
    const landing = landingByColumn.get(column)
    const control = button('', () => playLineAt(landing), 'drop-column')
    control.dataset.column = String(column + 1)
    control.disabled = !playable || landing === undefined
    const marks = []
    for (let position = column; position < game.board.length; position += game.width) {
      const side = game.board[position]
      const slot = document.createElement('span')
      slot.className = 'four-slot'
      slot.dataset.position = String(position)
      slot.setAttribute('aria-hidden', 'true')
      if (side !== null) {
        slot.textContent = player(side)
        slot.classList.add('side-' + side)
        if (game.winningLine?.includes(position)) slot.classList.add('winning')
        marks.push(slot.textContent)
      } else if (!control.disabled && position === landing) {
        slot.classList.add('landing')
      }
      control.append(slot)
    }
    const contents = marks.length ? 'Desde abajo, ' + marks.reverse().join(', ') : 'Vacía'
    control.setAttribute('aria-label', `Columna ${column + 1}. ${contents}.`)
    return control
  })
  grid.replaceChildren(...columns)
  if (activeColumn !== undefined) {
    const next = columns.find(control => !control.disabled && control.dataset.column === activeColumn) ??
      columns.find(control => !control.disabled)
    next?.focus({ preventScroll: true })
  }
}

function drawLineBoard() {
  const game = state.game
  const grid = element('board')
  const compactLine = state.kind === 'tic_tac_toe' || game.gravity
  grid.className = 'board line-board ' + (compactLine ? (game.gravity ? 'four' : 'three') : state.kind)
  const actions = state.kind === 'reversi' ? reversiActions(game) : lineBoardActions(game)
  if (game.gravity) {
    drawColumns(grid, game, actions)
    return
  }
  const legal = new Set(actions)
  grid.replaceChildren(...game.board.map((side, position) => {
    const mark = side === null ? '' : player(side)
    const cell = button(compactLine ? mark : '', () => playLineAt(position), mark ? `mark side-${side}` : 'empty')
    cell.dataset.position = String(position)
    if (game.winningLine?.includes(position)) cell.classList.add('winning')
    cell.disabled = !legal.has(position) || Boolean(cannotPlay())
    const coordinate = String.fromCharCode(97 + position % game.width) + (game.height - Math.floor(position / game.width))
    cell.setAttribute('aria-label', compactLine ? mark || `Casilla ${position + 1}` : `${coordinate}${mark ? ', ' + mark : ''}`)
    if (!compactLine && mark) {
      const disc = document.createElement('span')
      disc.className = 'flat-disc'
      disc.setAttribute('aria-hidden', 'true')
      cell.append(disc)
    }
    return cell
  }))
}

function board8() {
  const board = element('board')
  board.className = 'board eight ' + state.kind
  const chess = state.kind === 'chess'
  const legal = chess ? chessActions(state.game, state.selection) : checkerActions(state.game)
  const targets = legal.filter(move => move.from === state.selection)
  const squares = chess ? state.game.board.board().flat() : state.game.board
  const reverse = state.room?.seat === 1
  board.replaceChildren(...squares.map((_, displayIndex) => {
    const index = reverse ? 63 - displayIndex : displayIndex
    const piece = squares[index]
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
    node.dataset.square = coordinate
    if (destination.length) {
      node.classList.add('legal')
      if (destination.some(move => chess ? Boolean(move.captured) : move.capture !== null)) node.classList.add('capture')
    }
    if (state.game.lastMove?.includes(coordinate)) node.classList.add('last')
    if (chess && piece?.type === 'k' && piece.color === state.game.board.turn() && state.game.board.isCheck()) node.classList.add('in-check')
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
    if (displayIndex % 8 === 0) node.dataset.rank = coordinate[1]
    if (displayIndex >= 56) {
      const file = document.createElement('span')
      file.className = 'square-file'
      file.setAttribute('aria-hidden', 'true')
      file.textContent = coordinate[0]
      node.append(file)
    }
    node.setAttribute('aria-label', label)
    return node
  }))
}
const ludoBase = [[1,1], [1,10], [10,10], [10,1]]
function drawLudo() {
  const board = element('board')
  board.className = 'board ludo'
  const game = state.game
  const activeColors = new Set(game.pieces.map((_, seat) => ludoColor(game, seat)))
  const cellAt = (row, column) => row * 15 + column
  const cells = Array.from({ length: 225 }, (_, index) => {
    const cell = document.createElement('div')
    const row = Math.floor(index / 15), column = index % 15
    const isBase = (row < 6 || row > 8) && (column < 6 || column > 8)
    const corner = row < 6 ? (column < 6 ? 0 : 1) : (column > 8 ? 2 : 3)
    cell.className = 'cell'
    if (isBase) {
      cell.classList.add('base', 'color-' + corner)
      if (row % 9 > 0 && row % 9 < 5 && column % 9 > 0 && column % 9 < 5) cell.classList.add('base-inset')
      if (!activeColors.has(corner)) cell.classList.add('inactive')
    } else if (row >= 6 && row <= 8 && column >= 6 && column <= 8) {
      cell.classList.add('board-center')
    }
    return cell
  })
  for (const [index, [row, column]] of ludoTrack.entries()) {
    const cell = cells[cellAt(row, column)]
    cell.classList.add('track')
    if (index % 13 === 0) cell.classList.add('safe-start', 'color-' + index / 13)
    if (ludoSafe.has(index)) {
      const star = document.createElement('span')
      star.className = 'safe-mark'
      star.textContent = '★'
      star.setAttribute('aria-hidden', 'true')
      cell.append(star)
    }
  }
  ludoHome.forEach((lane, color) => lane.forEach(([row, column]) => cells[cellAt(row, column)].classList.add('lane', 'color-' + color)))
  function dock(color, token) {
    const [row, column] = ludoBase[color]
    return [row + 2 * (token >> 1), column + 2 * (token % 2)]
  }
  for (const color of activeColors) {
    for (let token = 0; token < 4; token++) cells[cellAt(...dock(color, token))].classList.add('dock')
  }
  const legal = new Set(ludoActions(game).map(move => move.token))
  const placed = new Map()
  for (const [side, pieces] of game.pieces.entries()) {
    const color = ludoColor(game, side)
    for (const [token, progress] of pieces.entries()) {
      const index = cellAt(...(progress < 0 ? dock(color, token) : ludoCell(game, side, progress)))
      const piece = { token, color, side, playable: !cannotPlay() && side === turn() && legal.has(token) }
      if (placed.has(index)) placed.get(index).push(piece)
      else placed.set(index, [piece])
    }
  }
  for (const [index, pieces] of placed) {
    const { token, color, side, playable } = pieces.find(piece => piece.playable) ?? pieces[0]
    const node = button(String(token + 1), () => moveToken(token), 'token color-' + color)
    node.dataset.token = side + '-' + token
    node.disabled = !playable
    const countLabel = pieces.length > 1 ? `. ${pieces.length} fichas en esta casilla` : ''
    node.setAttribute('aria-label', `${player(side)}, ficha ${token + 1}${countLabel}`)
    cells[index].append(node)
    if (pieces.length > 1) {
      const count = document.createElement('span')
      count.className = 'token-count'
      count.textContent = '×' + pieces.length
      count.setAttribute('aria-hidden', 'true')
      cells[index].append(count)
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
function gomokuScore(game, position) {
  const column = position % game.width
  const row = Math.floor(position / game.width)
  function longest(side) {
    return Math.max(...[[0, 1], [1, 0], [1, 1], [1, -1]].map(([dx, dy]) => {
      let length = 1
      for (const dir of [-1, 1]) {
        for (let x = column + dx * dir, y = row + dy * dir;
          x >= 0 && x < game.width && y >= 0 && y < game.height && game.board[y * game.width + x] === side;
          x += dx * dir, y += dy * dir) length++
      }
      return length
    }))
  }
  const own = longest(game.turn)
  const rival = longest(1 - game.turn)
  if (own >= 5) return 10000
  if (rival >= 5) return 9000
  return own * own * 5 + rival * rival * 4 - .15 * (Math.abs(column - 7) + Math.abs(row - 7))
}
function runBot() {
  if (!botTurn()) return
  if (state.kind === 'chess' && !tickChessClock()) return
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
  } else if (state.kind === 'gomoku') {
    const move = botChoice(lineBoardActions(state.game), position => gomokuScore(state.game, position))
    playLineBoard(state.game, move)
  } else if (state.kind === 'reversi') {
    const move = botChoice(reversiActions(state.game), position => {
      if ([0, 7, 56, 63].includes(position)) return 100
      const trial = structuredClone(state.game)
      playReversi(trial, position)
      return trial.flipped.length - reversiActions(trial).length * .4
    })
    playReversi(state.game, move)
  } else if (isLineGame()) {
    const move = botChoice(lineBoardActions(state.game), position => {
      const trial = structuredClone(state.game)
      playLineBoard(trial, position)
      if (trial.outcome?.winner === state.game.turn) return 100
      const rivalCanWin = lineBoardActions(trial).some(reply => {
        const replyGame = structuredClone(trial)
        return playLineBoard(replyGame, reply) && replyGame.outcome?.winner === trial.turn
      })
      return (rivalCanWin ? -50 : 0) - Math.abs(position % trial.width - (trial.width - 1) / 2)
    })
    playLineBoard(state.game, move)
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
  tickChessClock()
  const result = game.outcome
  const previous = state.lastRendered
  const motion = previous?.kind === state.kind && previous.room === state.room?.id &&
    game.history.length === previous.moves + 1 && !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  const board = element('board')
  const event = game.history.at(-1)
  const movingToken = event && typeof event === 'object' && 'token' in event ? `[data-token="${event.side}-${event.token}"]` : null
  const from = motion && (movingToken ?? (game.lastMove ? `[data-square="${game.lastMove[0]}"] .piece, [data-square="${game.lastMove[0]}"] .disc` : null))
  const bounds = from ? board.querySelector(from)?.getBoundingClientRect() : null
  state.lastRendered = { kind: state.kind, room: state.room?.id, moves: game.history.length }
  renderRoster(game)
  element('table').dataset.finished = String(Boolean(result))
  const lastRoll = game.lastDie ? ` · ${player(game.lastDie.side)} sacó ${game.lastDie.value}` : ''
  const onlineTurn = state.room ? (state.room.seat === turn() ? ' · Tu turno' : ' · Esperando jugada') : ''
  const moveHint = state.kind === 'connect_four' && !cannotPlay() ? ' · Elige una columna' :
    state.kind === 'reversi' && game.passed != null ? ` · ${player(game.passed)} pasan` : ''
  element('status').textContent = result ?
    `${result.winner === null ? 'Empate' : 'Gana ' + player(result.winner)} · ${result.reason}` :
    `Turno: ${player(turn())}${botTurn() ? ' · Novvin' : onlineTurn}${state.kind === 'chess' && game.board.isCheck() ? ' · Jaque' : ''}${lastRoll}${moveHint}`
  element('result-panel').hidden = !result
  element('result-panel').dataset.celebrate = String(Boolean(result && motion))
  if (result) {
    const mine = state.room?.seat ?? (state.bot ? 0 : -1)
    element('result-title').textContent = result.winner === null ? 'Empate' : mine < 0 ? `Gana ${player(result.winner)}` : mine === result.winner ? 'Victoria' : 'Derrota'
    element('result-detail').textContent = `${result.reason} · ${game.history.length} ${game.history.length === 1 ? 'acción' : 'acciones'}`
  }
  const ludo = state.kind === 'ludo'
  element('roll').hidden = !ludo || Boolean(result)
  element('roll').disabled = Boolean(cannotPlay()) || game.die !== null
  if (ludo) {
    element('die-face').textContent = game.lastDie ? '⚀⚁⚂⚃⚄⚅'[game.lastDie.value - 1] : '⚄'
    element('roll-label').textContent = game.die === null ? 'Lanzar dado' : 'Elige una ficha'
  }
  element('claim').hidden = state.kind !== 'chess' || !canClaimChessDraw(game) || Boolean(cannotPlay())
  element('resign').hidden = ludo || Boolean(result) || Boolean(cannotPlay())
  element('tokens').replaceChildren()
  if (ludo) drawLudo()
  else if (isLineGame()) drawLineBoard()
  else board8()
  if (motion) {
    const to = movingToken ?? (game.lastMove ? `[data-square="${game.lastMove[1]}"] .piece, [data-square="${game.lastMove[1]}"] .disc` : null)
    const moved = to && board.querySelector(to)
    if (moved && bounds) {
      const target = moved.getBoundingClientRect()
      moved.animate?.([{ transform: `translate(${bounds.left - target.left}px,${bounds.top - target.top}px)` }, { transform: 'none' }], { duration: 225, easing: 'ease-out' })
    } else if (ludo && event && 'roll' in event) {
      element('die-face').animate?.([{ transform: 'rotate(-90deg) scale(.58)' }, { transform: 'none' }], { duration: 300, easing: 'ease-out' })
    } else if (isLineGame()) {
      const mark = board.querySelector(`[data-position="${game.lastPosition}"]`)
      mark?.animate?.([{ transform: game.gravity ? 'translateY(-110%)' : 'scale(.65)', opacity: .4 }, { transform: 'none', opacity: 1 }], { duration: 220, easing: 'ease-out' })
      if (state.kind === 'reversi') for (const position of game.flipped) {
        const disc = board.querySelector(`[data-position="${position}"] .flat-disc`)
        disc?.animate?.([{ transform: 'rotateY(180deg)' }, { transform: 'rotateY(0deg)' }], { duration: 260, easing: 'ease-out' })
      }
    }
  }
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
