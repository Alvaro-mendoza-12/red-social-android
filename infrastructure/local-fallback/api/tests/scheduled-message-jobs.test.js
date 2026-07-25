const assert = require('assert/strict')
const {
  normalizeScheduledInput,
  retryDelaySeconds,
} = require('../src/scheduled-message-jobs')

function expectError(callback, message) {
  try {
    callback()
    assert.fail(`Expected ${message}`)
  } catch (error) {
    assert.equal(error.message, message)
  }
}

function run() {
  const now = Date.parse('2026-07-15T12:00:00.000Z')
  const input = normalizeScheduledInput({
    p_scope_type: 'DM',
    p_target_id: '123e4567-e89b-42d3-a456-426614174000',
    p_content: ' Hola ',
    p_content_type: 'text',
    p_scheduled_for: '2026-07-15T12:01:00.000Z',
    p_idempotency_key: 'web-request-1',
  }, now)

  assert.equal(input.scopeType, 'dm')
  assert.equal(input.content, 'Hola')
  assert.equal(input.scheduledFor, '2026-07-15T12:01:00.000Z')
  assert.equal(input.idempotencyKey, 'web-request-1')
  assert.equal(retryDelaySeconds(1), 5)
  assert.equal(retryDelaySeconds(4), 40)
  assert.equal(retryDelaySeconds(20), 900)

  expectError(() => normalizeScheduledInput({
    p_scope_type: 'dm',
    p_target_id: input.targetId,
    p_content: 'late',
    p_scheduled_for: '2026-07-15T11:59:00.000Z',
  }, now), 'SCHEDULE_TIME_MUST_BE_IN_FUTURE')

  expectError(() => normalizeScheduledInput({
    p_scope_type: 'dm',
    p_target_id: input.targetId,
    p_content: 'translate',
    p_translate_to: 'en',
    p_scheduled_for: '2026-07-15T12:01:00.000Z',
  }, now), 'TRANSLATION_PROVIDER_DISABLED')

  expectError(() => normalizeScheduledInput({
    p_scope_type: 'dm',
    p_target_id: input.targetId,
    p_content: 'media',
    p_media_url: 'javascript:alert(1)',
    p_scheduled_for: '2026-07-15T12:01:00.000Z',
  }, now), 'INVALID_MEDIA_URL')

  console.log('scheduled-message-jobs tests passed')
}

run()
