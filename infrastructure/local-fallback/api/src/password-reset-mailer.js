const nodemailer = require('nodemailer')

function createPasswordResetMailer(config = process.env, createTransport = nodemailer.createTransport) {
  const host = String(config.SMTP_HOST || '').trim()
  const from = String(config.SMTP_FROM || '').trim()
  const user = String(config.SMTP_USER || '').trim()
  const pass = String(config.SMTP_PASS || '')
  const port = Number(config.SMTP_PORT || 587)
  if (!host || !from || !user || !pass || !Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('SMTP password recovery requires SMTP_HOST, SMTP_PORT, SMTP_FROM, SMTP_USER and SMTP_PASS')
  }
  if (/[\r\n]/.test(from)) throw new Error('SMTP_FROM must be a single email sender')

  const transport = createTransport({
    host,
    port,
    secure: port === 465,
    requireTLS: port !== 465,
    auth: { user, pass },
    pool: true,
    maxConnections: 2,
    connectionTimeout: 5000,
    greetingTimeout: 5000,
    socketTimeout: 10000,
  })

  return {
    async deliver(email, token, expiresInMinutes, deliveryId) {
      const domain = from.match(/@([a-zA-Z0-9.-]+)/)?.[1] || 'novvin.invalid'
      const messageId = /^[a-f0-9-]{36}$/i.test(String(deliveryId || ''))
        ? `<novvin-recovery-${deliveryId}@${domain}>` : null
      await transport.sendMail({
        ...(messageId ? { messageId } : {}),
        from,
        to: email,
        subject: 'Novvin: recupera tu contraseña',
        text: [
          'Recibimos una solicitud para cambiar la contraseña de tu cuenta Novvin.',
          '',
          `Código de recuperación: ${token}`,
          `Este código vence en ${expiresInMinutes} minutos.`,
          '',
          'Si no solicitaste el cambio, ignora este mensaje. Nunca compartas el código.',
        ].join('\n'),
      })
    },
  }
}

module.exports = { createPasswordResetMailer }
