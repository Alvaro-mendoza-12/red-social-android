const THREAD_SUMMARY_MODEL = 'novvin-thread-extractive-v1'
const THREAD_SUMMARY_NOTICE = 'Este resumen es extractivo y puede omitir contexto o matices. Revisa las fuentes originales.'

const STOP_WORDS = new Set([
  'a', 'al', 'algo', 'ante', 'asi', 'aun', 'aunque', 'cada', 'como', 'con',
  'contra', 'cual', 'cuando', 'de', 'del', 'desde', 'donde', 'dos', 'el', 'ella',
  'ellas', 'ellos', 'en', 'entre', 'era', 'es', 'esa', 'ese', 'eso', 'esta',
  'este', 'esto', 'fue', 'ha', 'hay', 'la', 'las', 'le', 'les', 'lo', 'los',
  'mas', 'me', 'mi', 'mis', 'muy', 'ni', 'no', 'nos', 'o', 'otra', 'otro',
  'para', 'pero', 'por', 'porque', 'que', 'se', 'ser', 'si', 'sin', 'sobre',
  'son', 'su', 'sus', 'tambien', 'te', 'tiene', 'tu', 'un', 'una', 'uno', 'y',
  'ya',
])

function normalizeText(value) {
  return String(value || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function tokensOf(value) {
  return normalizeText(value)
    .split(' ')
    .filter((token) => token.length >= 3 && token.length <= 32 && !STOP_WORDS.has(token))
}

function sanitizeComment(comment, index) {
  const content = String(comment?.content || '').replace(/\s+/g, ' ').trim().slice(0, 2_000)
  const id = String(comment?.id || '').trim().slice(0, 160)
  if (!id || content.length < 3) return null
  return {
    id,
    parentId: String(comment?.parentId || '').trim().slice(0, 160) || null,
    authorName: String(comment?.authorName || 'Usuario').replace(/\s+/g, ' ').trim().slice(0, 100) || 'Usuario',
    content,
    createdAt: String(comment?.createdAt || '').trim().slice(0, 80),
    index,
    tokens: Array.from(new Set(tokensOf(content))),
  }
}

function jaccard(left, right) {
  if (left.length === 0 || right.length === 0) return 0
  const rightSet = new Set(right)
  const intersection = left.filter((token) => rightSet.has(token)).length
  return intersection / (new Set([...left, ...right])).size
}

function summarizeThread(comments, { maxBullets = 5 } = {}) {
  const cleanComments = (Array.isArray(comments) ? comments : [])
    .slice(0, 500)
    .map(sanitizeComment)
    .filter(Boolean)

  if (cleanComments.length < 3) {
    const error = new Error('THREAD_SUMMARY_NOT_ENOUGH_CONTENT')
    error.code = 'THREAD_SUMMARY_NOT_ENOUGH_CONTENT'
    error.status = 422
    throw error
  }

  const documentFrequency = new Map()
  for (const comment of cleanComments) {
    for (const token of comment.tokens) {
      documentFrequency.set(token, (documentFrequency.get(token) || 0) + 1)
    }
  }

  const scored = cleanComments.map((comment) => {
    const centrality = comment.tokens.reduce(
      (total, token) => total + Math.log1p(documentFrequency.get(token) || 0),
      0,
    )
    const informativeLength = Math.min(comment.content.length, 280) / 280
    const replyContext = comment.parentId ? 0.08 : 0
    return {
      ...comment,
      baseScore: centrality + informativeLength + replyContext,
    }
  })

  const targetCount = Math.max(2, Math.min(Number(maxBullets) || 5, 6, cleanComments.length))
  const selected = []
  const remaining = [...scored]
  while (selected.length < targetCount && remaining.length > 0) {
    remaining.sort((left, right) => {
      const leftOverlap = selected.reduce(
        (maximum, item) => Math.max(maximum, jaccard(left.tokens, item.tokens)),
        0,
      )
      const rightOverlap = selected.reduce(
        (maximum, item) => Math.max(maximum, jaccard(right.tokens, item.tokens)),
        0,
      )
      const leftAdjusted = left.baseScore - (leftOverlap * 4)
      const rightAdjusted = right.baseScore - (rightOverlap * 4)
      return rightAdjusted - leftAdjusted || left.index - right.index
    })
    selected.push(remaining.shift())
  }

  const topics = [...documentFrequency.entries()]
    .filter(([, count]) => count >= 2)
    .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))
    .slice(0, 8)
    .map(([token]) => token)

  return {
    kind: 'extractive',
    model: THREAD_SUMMARY_MODEL,
    notice: THREAD_SUMMARY_NOTICE,
    commentCount: cleanComments.length,
    topics,
    bullets: selected
      .sort((left, right) => left.index - right.index)
      .map((comment) => ({
        text: comment.content.slice(0, 320),
        sourceIds: [comment.id],
      })),
  }
}

module.exports = {
  THREAD_SUMMARY_MODEL,
  THREAD_SUMMARY_NOTICE,
  summarizeThread,
}
