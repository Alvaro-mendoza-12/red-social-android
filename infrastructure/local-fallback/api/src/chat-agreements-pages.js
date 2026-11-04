'use strict'

const { agreementId: id, agreementFail: fail } = require('./chat-agreements-domain')
const timestamp = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/
const projection = `id::text,state,to_char(created_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS position_time`

function validTime(value) {
  return typeof value === 'string' && timestamp.test(value) && !value.startsWith('0000-') &&
    Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value.slice(0, 23) + 'Z'
}
function encodeAgreementCursor(chatId, archived, row, direction) {
  if (!id(chatId) || !id(row.id) || !validTime(row.position_time) || !['older', 'newer'].includes(direction)) throw Error('Invalid agreement page position')
  return Buffer.from(JSON.stringify({ v: 1, chatId: id(chatId), archived, at: row.position_time, id: id(row.id), direction })).toString('base64url')
}
function decodeCursor(value, chatId, archived) {
  try {
    if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,512}$/.test(value)) throw Error()
    const bytes = Buffer.from(value, 'base64url')
    if (bytes.toString('base64url') !== value) throw Error()
    const token = JSON.parse(bytes.toString('utf8'))
    if (!token || typeof token !== 'object' || Array.isArray(token) ||
        Object.keys(token).sort().join(',') !== 'archived,at,chatId,direction,id,v' ||
        token.v !== 1 || token.chatId !== chatId || token.archived !== archived ||
        !id(token.id) || id(token.id) !== token.id || !validTime(token.at) || !['older', 'newer'].includes(token.direction)) throw Error()
    return token
  } catch (_) { throw fail('AGREEMENT_INVALID_CURSOR') }
}
function agreementPageQuery(query, chatId) {
  if (!query || typeof query !== 'object' || Array.isArray(query) ||
      Object.keys(query).some(key => !['archived', 'limit', 'cursor', 'focus'].includes(key))) throw fail('AGREEMENT_INVALID_PAGE')
  if (query.archived !== undefined && !['true', 'false'].includes(query.archived)) throw fail('AGREEMENT_INVALID_PAGE')
  if (query.limit !== undefined && !['25', '50'].includes(query.limit)) throw fail('AGREEMENT_INVALID_PAGE')
  if (query.focus !== undefined && !id(query.focus)) throw fail('AGREEMENT_INVALID_PAGE')
  const archived = query.archived === 'true'
  return { archived, limit: query.limit === '25' ? 25 : 50, focus: query.focus ? id(query.focus) : null,
    cursor: query.cursor ?? null, boundary: query.cursor === undefined ? null : decodeCursor(query.cursor, chatId, archived) }
}

/** One page per direction; timestamps keep PostgreSQL's microseconds, never Date's millisecond rounding. */
async function readAgreementPage(db, chatId, query) {
  async function select(boundary) {
    const params = [chatId, query.archived]
    let where = ''
    if (boundary) {
      params.push(boundary.at, boundary.id)
      where = ` AND (created_at,id) ${boundary.direction === 'newer' ? '>' : '<'} ($3::timestamptz,$4::uuid)`
    }
    params.push(query.limit + 1)
    const order = boundary?.direction === 'newer' ? 'ASC' : 'DESC'
    const rows = (await db.query(`SELECT ${projection} FROM experimental_chat_agreements
      WHERE chat_id=$1 AND archived=$2${where} ORDER BY created_at ${order},id ${order} LIMIT $${params.length}`, params)).rows.slice(0, query.limit)
    return order === 'ASC' ? rows.reverse() : rows
  }
  let rows = await select(query.boundary)
  const reset = Boolean(query.boundary && !rows.length)
  if (reset) rows = await select(null)
  let nextCursor = null, previousCursor = null
  if (rows.length) {
    const first = rows[0], last = rows.at(-1)
    const edges = (await db.query(`SELECT
      EXISTS(SELECT 1 FROM experimental_chat_agreements WHERE chat_id=$1 AND archived=$2
        AND (created_at,id)>($3::timestamptz,$4::uuid)) AS newer,
      EXISTS(SELECT 1 FROM experimental_chat_agreements WHERE chat_id=$1 AND archived=$2
        AND (created_at,id)<($5::timestamptz,$6::uuid)) AS older`,
    [chatId, query.archived, first.position_time, first.id, last.position_time, last.id])).rows[0]
    if (edges.newer) previousCursor = encodeAgreementCursor(chatId, query.archived, first, 'newer')
    if (edges.older) nextCursor = encodeAgreementCursor(chatId, query.archived, last, 'older')
  }
  let focus = query.focus ? rows.find(row => row.id === query.focus)?.state : null
  if (query.focus && !focus) focus = (await db.query('SELECT state FROM experimental_chat_agreements WHERE chat_id=$1 AND id=$2::uuid', [chatId, query.focus])).rows[0]?.state
  return { states: rows.map(row => row.state), focus: focus || null, focusMissing: Boolean(query.focus && !focus),
    cursor: reset ? null : query.cursor, nextCursor, previousCursor, archived: query.archived, reset }
}

module.exports = { agreementPageQuery, readAgreementPage, encodeAgreementCursor }
