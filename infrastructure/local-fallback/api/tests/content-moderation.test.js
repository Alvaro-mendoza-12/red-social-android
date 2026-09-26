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
