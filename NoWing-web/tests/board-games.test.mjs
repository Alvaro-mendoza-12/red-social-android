import test from 'node:test'
import assert from 'node:assert/strict'
import { createChess, chessActions, playChess, claimChessDraw, canClaimChessDraw,
  createCheckers, checkerActions, playCheckers,
  createLudo, ludoActions, ludoCell, rollLudo, playLudo } from '../../infrastructure/local-fallback/api/src/board-games/engine/board-rules.js'

test('chess validates turn and finishes Fool’s mate', () => {
  const game = createChess()
  assert.equal(chessActions(game).length, 20)
  assert.equal(playChess(game, { from: 'e7', to: 'e5' }), false)
  assert.equal(playChess(game, { to: 'e4' }), false)
  for (const [from, to] of [['f2','f3'],['e7','e5'],['g2','g4'],['d8','h4']]) {
    assert.equal(playChess(game, { from, to }), true)
  }
  assert.deepEqual(game.outcome, { winner: 1, reason: 'Jaque mate' })
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
