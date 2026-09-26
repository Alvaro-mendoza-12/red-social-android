'use strict'

const BLOCKED_PATTERNS = [
  /\b(?:kill\s+yourself|m[aá]tate|suic[ií]date)\b/iu,
  /\b(?:voy\s+a\s+matarte|pornograf[ií]a\s+infantil|nude\s+minors|cp\s+link)\b/iu,
  /\b(?:bomb\s+tutorial|terror\s+guide|credit\s+card\s+dump|ssn\s+leak)\b/iu,
]

const LEET_CLASS = Object.freeze({
  a: '[aáàäâ4@]', b: '[b8]', e: '[eéèëê3]', g: '[g9]', i: '[iíìïî1!|]',
  o: '[oóòöô0]', s: '[s5$]', t: '[t7+]', u: '[uúùüûv]',
})

function obfuscatedPattern(term) {
  const canonical = String(term).normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z0-9]/gu, '')
  const body = [...canonical].map((character) => `${LEET_CLASS[character] || character}+`).join('[\\W_]*')
  return new RegExp(`(?<![\\p{L}\\p{N}])${body}(?![\\p{L}\\p{N}])`, 'giu')
}

const MASKED_PATTERNS = [
  'idiot', 'idiota', 'stupid', 'estupido', 'estupida', 'imbecil', 'imbeciles',
  'pendejo', 'pendeja', 'mierda', 'cabron', 'puta', 'puto', 'maricon',
  'malparido', 'malparida', 'hijueputa', 'hijo de puta',
].map(obfuscatedPattern)

const SUSPICIOUS_LINK = /https?:\/\/\S*(?:grabify|iplogger|bit\.ly|cutt\.ly|tinyurl\.com|shorturl)\S*/iu

function normalizeText(value, maxLength = 1500) {
  return String(value == null ? '' : value)
    .replace(/[\u200B\u200C\u200D\uFEFF]/gu, '')
    .replace(/\r\n/g, '\n')
    .trim()
    .slice(0, maxLength)
}

function moderatePublicComment(value) {
  const original = normalizeText(value)
  if (!original) return { verdict: 'allow', text: '', flags: [] }
  if (BLOCKED_PATTERNS.some((pattern) => pattern.test(original))) {
    return { verdict: 'block', text: '', flags: ['blocked_term'] }
  }
  if (SUSPICIOUS_LINK.test(original)) {
    return { verdict: 'block', text: '', flags: ['suspicious_link'] }
  }
  const links = original.match(/https?:\/\/\S+/giu) || []
  if (links.length >= 4) {
    return { verdict: 'block', text: '', flags: ['link_spam'] }
  }

  const flags = []
  let text = original
  for (const pattern of MASKED_PATTERNS) {
    pattern.lastIndex = 0
    if (pattern.test(text)) {
      pattern.lastIndex = 0
      text = text.replace(pattern, '***')
      flags.push('masked_term')
    }
  }
  text = text.replace(/(.)\1{7,}/gu, (match) => {
    flags.push('repeat_spam')
    return match.slice(0, 4)
  })

  return {
    verdict: flags.length ? 'sanitize' : 'allow',
    text,
    flags: [...new Set(flags)],
  }
}

module.exports = { moderatePublicComment }
