import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createChess, chessActions, playChess, claimChessDraw, canClaimChessDraw,
  createCheckers, checkerActions, playCheckers,
  createLudo, ludoActions, ludoCell, rollLudo, playLudo,
  createLineBoard, lineBoardActions, playLineBoard } from '../../infrastructure/local-fallback/api/src/board-games/engine/board-rules.js'

test('chess validates turn and finishes Fool’s mate', () => {
  const game = createChess()
  assert.equal(chessActions(game).length, 20)
  assert.equal(playChess(game, { from: 'e7', to: 'e5' }), false)
  assert.equal(playChess(game, { to: 'e4' }), false)
  for (const [from, to] of [['f2','f3'],['e7','e5'],['g2','g4'],['d8','h4']]) {
    assert.equal(playChess(game, { from, to }), true)
  }
  assert.deepEqual(game.outcome, { winner: 1, reason: 'Jaque mate' })
  assert.deepEqual(game.lastMove, ['d8', 'h4'])
  assert.equal(chessActions(game).length, 0)
})

test('chess supports en passant, castling and the four promotions', () => {
  const ep = createChess()
  for (const [from, to] of [['e2','e4'],['a7','a6'],['e4','e5'],['d7','d5'],['e5','d6']]) {
    assert.equal(playChess(ep, { from, to }), true)
  }
  assert.equal(ep.board.get('d5'), undefined)
  const castle = createChess()
  castle.board.load('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1')
  assert.equal(playChess(castle,{ from:'e1', to:'g1' }),true)
  assert.equal(castle.board.get('f1').type,'r')
  const promotion = createChess()
  promotion.board.load('7k/P7/8/8/8/8/8/7K w - - 0 1')
  assert.deepEqual(new Set(chessActions(promotion,'a7').map(move => move.promotion)),new Set(['b','n','q','r']))
  assert.equal(playChess(promotion,{ from:'a7',to:'a8',promotion:'n' }),true)
  assert.equal(promotion.board.get('a8').type,'n')
})

test('chess distinguishes a claim from automatic repetition', () => {
  const game = createChess()
  for(let i=0;i<2;i++) {
    for(const [from,to] of [['g1','f3'],['g8','f6'],['f3','g1'],['f6','g8']]) assert.ok(playChess(game,{from,to}))
  }
  assert.equal(canClaimChessDraw(game), true)
  assert.equal(game.outcome,null)
  assert.ok(claimChessDraw(game))
  assert.equal(game.outcome.winner,null)
})

test('checkers requires captures and chains the same piece', () => {
  const game = createCheckers()
  game.board.fill(null)
  game.board[42]={side:0,king:false}
  game.board[56]={side:0,king:false}
  game.board[35]={side:1,king:false}
  game.board[21]={side:1,king:false}
  assert.deepEqual(checkerActions(game), [{from:42,to:28,capture:35}])
  assert.equal(playCheckers(game,56,49),false)
  assert.ok(playCheckers(game,42,28))
  assert.equal(game.forced,28)
  assert.ok(playCheckers(game,28,14))
  assert.equal(game.outcome?.winner,0)
  assert.deepEqual(game.lastMove, ['e5', 'g7'])
})

test('checkers crowns on the back rank and ends the turn', () => {
  const game=createCheckers()
  game.board.fill(null)
  game.board[17]={side:0,king:false}
  game.board[10]={side:1,king:false}
  game.board[12]={side:1,king:false}
  assert.ok(playCheckers(game,17,3))
  assert.equal(game.board[3].king,true)
  assert.equal(game.turn,1)
  assert.equal(game.forced,null)
})

test('ludo enforces launch, safe squares and exact finish', () => {
  const game=createLudo(3)
  assert.equal(rollLudo(game,7),false)
  assert.ok(rollLudo(game,2))
  assert.equal(game.turn,1)
  assert.ok(rollLudo(game,6))
  assert.equal(ludoActions(game).length,4)
  assert.ok(playLudo(game,0))
  assert.equal(game.turn,1)
  game.turn=0
  game.pieces[0][0]=6
  game.pieces[1][0]=47
  assert.ok(rollLudo(game,2))
  assert.ok(playLudo(game,0))
  assert.equal(game.pieces[1][0],47)
  game.turn=0
  game.pieces[0][0]=55
  assert.ok(rollLudo(game,2))
  assert.deepEqual(ludoActions(game),[])
  assert.equal(game.turn,1)
})

test('ludo captures and completes ranked results', () => {
  const game=createLudo(3)
  game.pieces[0][0]=3
  game.pieces[1][0]=43
  assert.ok(rollLudo(game,1))
  assert.ok(playLudo(game,0))
  assert.equal(game.pieces[1][0],-1)
  game.turn=0
  game.pieces[0]=[56,56,56,55]
  assert.ok(rollLudo(game,1)); assert.ok(playLudo(game,3))
  assert.deepEqual(game.rankings,[0])
  game.pieces[1]=[56,56,56,55]
  assert.ok(rollLudo(game,1)); assert.ok(playLudo(game,3))
  assert.deepEqual(game.rankings,[0,1,2])
  assert.equal(game.outcome.winner,0)
  assert.deepEqual(ludoCell(game,0,56),[7,6])
})

test('three in a row ends on the first winning line and rejects occupied squares', () => {
  const game = createLineBoard('tic_tac_toe')
  assert.ok(playLineBoard(game, 0))
  assert.equal(playLineBoard(game, 0), false)
  for (const position of [3, 1, 4, 2]) assert.ok(playLineBoard(game, position))
  assert.deepEqual(game.outcome, { winner: 0, reason: 'Línea completa' })
  assert.deepEqual(game.winningLine, [0, 1, 2])
  assert.deepEqual(lineBoardActions(game), [])
})

test('four in a row requires gravity and wins on a vertical sequence', () => {
  const game = createLineBoard('connect_four')
  assert.equal(playLineBoard(game, 0), false)
  for (const position of [35, 36, 28, 29, 21, 22, 14]) assert.ok(playLineBoard(game, position))
  assert.equal(game.outcome.winner, 0)
  assert.deepEqual(game.winningLine, [14, 21, 28, 35])
})

test('four in a row highlights a diagonal without including its supporting pieces', () => {
  const game = createLineBoard('connect_four')
  for (const position of [35, 29, 23, 31]) game.board[position] = 0
  for (const position of [36, 37, 30, 38, 24]) game.board[position] = 1
  assert.ok(playLineBoard(game, 17))
  assert.deepEqual(game.winningLine, [35, 29, 23, 17])
})

test('four in a row accepts a touch anywhere in a column and preserves keyboard focus', async t => {
  const require = createRequire(import.meta.url)
  const { JSDOM } = createRequire(require.resolve('jest-environment-jsdom'))('jsdom')
  const assets = new URL('../public/board-games/', import.meta.url)
  const engine = new URL('../../infrastructure/local-fallback/api/src/board-games/engine/', import.meta.url)
  const markup = await readFile(new URL('board-games.html', assets), 'utf8')
  const dom = new JSDOM(markup, { url: 'https://appassets.androidplatform.net/board-games.html?mode=android' })
  const { window } = dom
  window.scrollTo = () => {}
  window.document.getElementById('promotion').close = () => {}
  t.after(() => {
    for (const key of ['window', 'document', 'location']) delete globalThis[key]
    window.close()
  })
  for (const key of ['window', 'document', 'location']) {
    Object.defineProperty(globalThis, key, { value: window[key], configurable: true })
  }
  // Gradle packages these modules together. Resolve the same imports in the source tree.
  const source = await readFile(new URL('board-games.js', assets), 'utf8')
  const module = source.replace(/'\.\/(board-(?:rules|match|online)\.js)'/g,
    (_, file) => JSON.stringify(new URL(file, file === 'board-online.js' ? assets : engine).href))
  await import('data:text/javascript;charset=utf-8,' + encodeURIComponent(module))

  const document = window.document
  document.querySelector('[data-game="connect_four"]').click()
  document.getElementById('setup').dispatchEvent(new window.Event('submit', { cancelable: true }))
  const grid = document.getElementById('board')
  const column = number => grid.querySelector(`[data-column="${number}"]`)
  assert.equal(grid.querySelectorAll('button').length, 7)
  assert.equal(column(1).getAttribute('aria-label'), 'Columna 1. Vacía.')
  assert.equal(column(1).lastElementChild.classList.contains('landing'), true)
  column(1).focus()
  column(1).firstElementChild.click()
  assert.equal(column(1).lastElementChild.textContent, 'X')
  assert.equal(document.activeElement, column(1))
  assert.equal(column(1).getAttribute('aria-label'), 'Columna 1. Desde abajo, X.')

  for (let remaining = 5; remaining > 0; remaining--) column(1).click()
  assert.equal(column(1).disabled, true)
  assert.equal(column(1).querySelector('.landing'), null)
  assert.equal(column(2).disabled, false)
  assert.equal(document.activeElement, column(2))

  document.getElementById('restart').click()
  for (const move of [1, 2, 1, 2, 1, 2, 1]) column(move).click()
  assert.match(document.getElementById('status').textContent, /Gana X/)
  assert.equal(grid.querySelectorAll('.four-slot.winning').length, 4)
  assert.equal(grid.querySelectorAll('button:disabled').length, 7)
})
