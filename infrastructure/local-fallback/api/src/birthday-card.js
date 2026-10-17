/** Birthday letters use the existing authorized, durable message pipeline. */
function birthdayCardContent(content, { kind = 'dm', mediaUrl = null, pollId = null } = {}) {
  const invalid = () => Object.assign(new Error('BIRTHDAY_CARD_INVALID'), { status: 400 })
  if (kind !== 'dm' || mediaUrl || pollId || typeof content !== 'string' || content.length > 5000) throw invalid()
  let value
  try { value = JSON.parse(content) } catch { throw invalid() }
  if (!value || value.version !== 1 || value.kind !== 'birthday' || typeof value.message !== 'string') throw invalid()
  const message = value.message.trim()
  if (!message || [...message].length > 600 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(message)) throw invalid()
  if (!['confetti', 'sunrise', 'garden'].includes(value.theme)) throw invalid()
  return JSON.stringify({ version: 1, kind: 'birthday', message, theme: value.theme })
}
module.exports = { birthdayCardContent }
