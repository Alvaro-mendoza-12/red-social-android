'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const {
  MINIMUM_REGISTRATION_AGE,
  parseIsoBirthDate,
  isBirthDateMinimumAge,
  validateRegistrationRequest,
} = require('../src/registration-security')

test('parseIsoBirthDate rejects invalid formats and impossible calendar dates', () => {
  assert.equal(parseIsoBirthDate('').provided, false)
  assert.equal(parseIsoBirthDate('not-a-date').valid, false)
  assert.equal(parseIsoBirthDate('2025-02-29').valid, false)
  assert.equal(parseIsoBirthDate('1899-12-31').valid, false)
  assert.equal(parseIsoBirthDate('2004-02-29').valid, true)
})

test('isBirthDateMinimumAge enforces 13 years inclusive on birthday matching Android', () => {
  const now = new Date('2026-11-12T12:00:00Z')
  assert.equal(MINIMUM_REGISTRATION_AGE, 13)
  assert.equal(isBirthDateMinimumAge(parseIsoBirthDate('2013-11-12'), now), true)
  assert.equal(isBirthDateMinimumAge(parseIsoBirthDate('2013-11-13'), now), false)
  assert.equal(isBirthDateMinimumAge(parseIsoBirthDate('2026-11-13'), now), false)
})

test('validateRegistrationRequest rejects honeypot, underage dates and optional Turnstile failures', async () => {
  const now = new Date('2026-11-12T12:00:00Z')

  const trap = await validateRegistrationRequest({
    body: { website: 'https://spam.invalid' },
    now,
  })
  assert.equal(trap.ok, false)
  assert.equal(trap.status, 400)
  assert.equal(trap.error, 'BOT_TRAP_TRIGGERED')

  const underage = await validateRegistrationRequest({
    body: { birthDate: '2014-01-01' },
    now,
  })
  assert.equal(underage.ok, false)
  assert.equal(underage.error, 'UNDERAGE_NOT_ALLOWED')

  const badDate = await validateRegistrationRequest({
    body: { birthDate: '2010-02-30' },
    now,
  })
  assert.equal(badDate.ok, false)
  assert.equal(badDate.error, 'INVALID_BIRTH_DATE')

  const valid = await validateRegistrationRequest({
    body: { birthDate: '2001-05-20', website: '' },
    isWebClient: true,
    env: {},
    now,
  })
  assert.deepEqual(valid, { ok: true, birthDate: '2001-05-20' })

  const missingCaptcha = await validateRegistrationRequest({
    body: { birthDate: '2001-05-20' },
    isWebClient: true,
    env: { TURNSTILE_SECRET_KEY: 'secret-test' },
    now,
  })
  assert.equal(missingCaptcha.ok, false)
  assert.equal(missingCaptcha.status, 403)
  assert.equal(missingCaptcha.error, 'CAPTCHA_REQUIRED')

  let capturedForm = ''
  const passedCaptcha = await validateRegistrationRequest({
    body: { birthDate: '2001-05-20', turnstileToken: 'tok-ok' },
    isWebClient: true,
    remoteIp: '203.0.113.10',
    env: { TURNSTILE_SECRET_KEY: 'secret-test' },
    now,
    fetchImpl: async (_url, init) => {
      capturedForm = String(init?.body || '')
      return { ok: true, json: async () => ({ success: true }) }
    },
  })
  assert.deepEqual(passedCaptcha, { ok: true, birthDate: '2001-05-20' })
  assert.match(capturedForm, /secret=secret-test/)
  assert.match(capturedForm, /response=tok-ok/)
  assert.match(capturedForm, /remoteip=203\.0\.113\.10/)

  const failedCaptcha = await validateRegistrationRequest({
    body: { birthDate: '2001-05-20', turnstileToken: 'tok-bad' },
    isWebClient: true,
    env: { TURNSTILE_SECRET_KEY: 'secret-test' },
    now,
    fetchImpl: async () => ({ ok: true, json: async () => ({ success: false }) }),
  })
  assert.equal(failedCaptcha.ok, false)
  assert.equal(failedCaptcha.status, 403)
  assert.equal(failedCaptcha.error, 'CAPTCHA_VERIFICATION_FAILED')
})
