'use strict'

const MINIMUM_REGISTRATION_AGE = 13
const TURNSTILE_VERIFY_URL = 'https://challenges.cloudflare.com/turnstile/v0/siteverify'

function daysInUtcMonth(year, monthOneBased) {
  return new Date(Date.UTC(year, monthOneBased, 0)).getUTCDate()
}

function parseIsoBirthDate(rawValue) {
  const value = String(rawValue ?? '').trim()
  if (!value) return { provided: false, valid: false, value: '' }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return { provided: true, valid: false, value }
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  if (year < 1900 || month < 1 || month > 12 || day < 1) {
    return { provided: true, valid: false, value }
  }
  const date = new Date(Date.UTC(year, month - 1, day))
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return { provided: true, valid: false, value }
  }
  return { provided: true, valid: true, value, year, month, day, date }
}

function isBirthDateMinimumAge(parsed, now = new Date(), minAge = MINIMUM_REGISTRATION_AGE) {
  if (!parsed || !parsed.valid) return false
  const todayYear = now.getUTCFullYear()
  const todayMonth = now.getUTCMonth() + 1
  const todayDay = now.getUTCDate()
  const todayUtc = Date.UTC(todayYear, todayMonth - 1, todayDay)
  if (parsed.date.getTime() > todayUtc) return false
  const cutoffYear = todayYear - minAge
  const cutoffDay = Math.min(todayDay, daysInUtcMonth(cutoffYear, todayMonth))
  const cutoffUtc = Date.UTC(cutoffYear, todayMonth - 1, cutoffDay)
  return parsed.date.getTime() <= cutoffUtc
}

function isTurnstileConfigured(env = process.env) {
  return String(env?.TURNSTILE_SECRET_KEY || '').trim().length > 0
}

async function verifyTurnstileToken({
  token,
  secretKey,
  remoteIp = '',
  fetchImpl = globalThis.fetch,
}) {
  const secret = String(secretKey || '').trim()
  const responseToken = String(token || '').trim()
  if (!secret) return { ok: true, skipped: true }
  if (!responseToken) {
    return {
      ok: false,
      status: 403,
      error: 'CAPTCHA_REQUIRED',
      message: 'Completa la verificación de seguridad antes de crear tu cuenta.',
    }
  }
  if (typeof fetchImpl !== 'function') {
    return {
      ok: false,
      status: 503,
      error: 'CAPTCHA_UNAVAILABLE',
      message: 'No se pudo comprobar la verificación de seguridad.',
    }
  }

  const form = new URLSearchParams()
  form.set('secret', secret)
  form.set('response', responseToken)
  if (remoteIp) form.set('remoteip', String(remoteIp))

  try {
    const response = await fetchImpl(TURNSTILE_VERIFY_URL, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
    })
    if (!response.ok) {
      return {
        ok: false,
        status: 503,
        error: 'CAPTCHA_UNAVAILABLE',
        message: 'No se pudo comprobar la verificación de seguridad.',
      }
    }
    const payload = await response.json().catch(() => null)
    if (!payload || payload.success !== true) {
      return {
        ok: false,
        status: 403,
        error: 'CAPTCHA_VERIFICATION_FAILED',
        message: 'La verificación de seguridad expiró o no es válida. Intenta de nuevo.',
      }
    }
    return { ok: true, skipped: false }
  } catch {
    return {
      ok: false,
      status: 503,
      error: 'CAPTCHA_UNAVAILABLE',
      message: 'No se pudo comprobar la verificación de seguridad.',
    }
  }
}

async function validateRegistrationRequest({
  body = {},
  isWebClient = false,
  remoteIp = '',
  env = process.env,
  now = new Date(),
  fetchImpl = globalThis.fetch,
} = {}) {
  const honeypot = String(body?.website ?? body?.company ?? '').trim()
  if (honeypot) {
    return {
      ok: false,
      status: 400,
      error: 'BOT_TRAP_TRIGGERED',
      message: 'No se pudo procesar la solicitud de registro.',
    }
  }

  const rawBirthDate = body?.birthDate ?? body?.birth_date
  const parsedBirthDate = parseIsoBirthDate(rawBirthDate)
  const requireBirthDate = String(env?.REQUIRE_REGISTRATION_BIRTH_DATE || '').toLowerCase() === 'true'
  if (parsedBirthDate.provided || requireBirthDate) {
    if (!parsedBirthDate.valid) {
      return {
        ok: false,
        status: 400,
        error: 'INVALID_BIRTH_DATE',
        message: 'Ingresa una fecha de nacimiento válida con formato AAAA-MM-DD.',
      }
    }
    if (!isBirthDateMinimumAge(parsedBirthDate, now, MINIMUM_REGISTRATION_AGE)) {
      return {
        ok: false,
        status: 400,
        error: 'UNDERAGE_NOT_ALLOWED',
        message: `Debes tener al menos ${MINIMUM_REGISTRATION_AGE} años para crear una cuenta.`,
      }
    }
  }

  const secretKey = String(env?.TURNSTILE_SECRET_KEY || '').trim()
  const turnstileToken = String(
    body?.turnstileToken ?? body?.['cf-turnstile-response'] ?? '',
  ).trim()
  if (secretKey && (isWebClient || turnstileToken)) {
    const captchaCheck = await verifyTurnstileToken({
      token: turnstileToken,
      secretKey,
      remoteIp,
      fetchImpl,
    })
    if (!captchaCheck.ok) return captchaCheck
  }

  return {
    ok: true,
    birthDate: parsedBirthDate.valid ? parsedBirthDate.value : '',
  }
}

module.exports = {
  MINIMUM_REGISTRATION_AGE,
  TURNSTILE_VERIFY_URL,
  parseIsoBirthDate,
  isBirthDateMinimumAge,
  isTurnstileConfigured,
  verifyTurnstileToken,
  validateRegistrationRequest,
}
