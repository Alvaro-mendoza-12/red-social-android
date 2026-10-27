const test = require('node:test')
const assert = require('node:assert/strict')
const nodemailer = require('nodemailer')

test('nested comments cannot replace the intended email envelope recipient', async () => {
  // Stream transport creates the message in memory; no SMTP server or recipient is contacted.
  const transport = nodemailer.createTransport({ streamTransport: true, buffer: true })
  try {
    const result = await transport.sendMail({
      from: 'Novvin QA <qa@novvin.invalid>',
      to: 'Recipient (outer(inner) <decoy@untrusted.invalid>) <allowed@novvin.invalid>',
      subject: 'Offline recipient regression',
      text: 'Synthetic fixture',
    })
    assert.deepEqual(result.envelope.to, ['allowed@novvin.invalid'])
  } finally {
    transport.close()
  }
})
