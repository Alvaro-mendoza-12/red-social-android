const test = require('node:test')
const assert = require('node:assert/strict')
const { createPasswordResetMailer } = require('../src/password-reset-mailer')

test('SMTP recovery requires a configured sender and encrypted connection', async () => {
  let transportOptions
  let deliveredMessage
  const mailer = createPasswordResetMailer({
    SMTP_HOST: 'mail.example.org',
    SMTP_PORT: '587',
    SMTP_FROM: 'Novvin <no-reply@example.org>',
    SMTP_USER: 'mailer',
    SMTP_PASS: 'secret',
  }, (options) => {
    transportOptions = options
    return { sendMail: async (message) => { deliveredMessage = message } }
  })

  await mailer.deliver('person@example.org', 'test-token', 30)
  assert.equal(transportOptions.requireTLS, true)
  assert.equal(transportOptions.secure, false)
  assert.equal(deliveredMessage.to, 'person@example.org')
  assert.match(deliveredMessage.text, /test-token/)
  assert.match(deliveredMessage.text, /30 minutos/)
})

test('SMTP recovery rejects missing credentials and header injection', () => {
  assert.throws(() => createPasswordResetMailer({ SMTP_HOST: 'mail.example.org' }), /requires/)
  assert.throws(() => createPasswordResetMailer({
    SMTP_HOST: 'mail.example.org',
    SMTP_FROM: 'sender@example.org\r\nBcc: attacker@example.org',
    SMTP_USER: 'mailer',
    SMTP_PASS: 'secret',
  }), /single email sender/)
})
