'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { moderatePublicComment } = require('../src/content-moderation')

test('masks Spanish insults without dropping the comment', () => {
  const result = moderatePublicComment('Eres un imbécil y un estúpido')
  assert.equal(result.verdict, 'sanitize')
  assert.equal(result.text.includes('imbécil'), false)
  assert.equal(result.text.includes('estúpido'), false)
})

test('blocks threats and suspicious links', () => {
  assert.equal(moderatePublicComment('voy a matarte').verdict, 'block')
  assert.equal(moderatePublicComment('abre https://bit.ly/abc').verdict, 'block')
})

test('masks obfuscated insults without censoring benign compliments', () => {
  const result = moderatePublicComment('eres p.v.t.4 e 1mb3c1l')
  assert.equal(result.verdict, 'sanitize')
  assert.equal(result.text.includes('p.v.t.4'), false)
  assert.equal(result.text.includes('1mb3c1l'), false)
  assert.equal(moderatePublicComment('Qué bonito, quedó muy rico.').verdict, 'allow')
})

test('resists adversarial ReDoS symbol repetition in linear time', () => {
  const payloads = [
    'e' + 's' + '$'.repeat(300) + 't' + '+'.repeat(300) + 'u' + 'p' + '!'.repeat(300) + 'x',
    'i' + '!'.repeat(400) + 'd' + '!'.repeat(400) + 'o' + 't' + '+'.repeat(400) + 'x',
    'a' + '@'.repeat(1400) + 'z',
  ]
  for (const payload of payloads) {
    const started = performance.now()
    moderatePublicComment(payload)
    const elapsedMs = performance.now() - started
    assert.ok(elapsedMs < 50, `Expected linear-time moderation, got ${elapsedMs.toFixed(2)}ms`)
  }
})
