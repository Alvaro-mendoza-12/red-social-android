'use strict'

/** Called inside the existing hard-delete transaction and its table locks. */
async function purgeChatAgreementsForAccounts(db, identities) {
  const ids = [...new Set(identities.map(value => String(value || '').trim().toLowerCase()).filter(Boolean))]
  if (!ids.length) return { agreements: 0, operations: 0 }
  const present = (await db.query(`SELECT to_regclass('public.experimental_chat_agreements') AS agreements,
    to_regclass('public.experimental_chat_agreement_operations') AS operations`)).rows[0]
  if (!present?.agreements) return { agreements: 0, operations: 0 }
  let operations = 0
  if (present.operations) operations = Number((await db.query(`DELETE FROM experimental_chat_agreement_operations
    WHERE lower(actor_id)=ANY($1::text[])`, [ids])).rowCount) || 0
  const agreements = Number((await db.query(`DELETE FROM experimental_chat_agreements agreement
    WHERE lower(author_id)=ANY($1::text[]) OR EXISTS (
      SELECT 1 FROM jsonb_array_elements(agreement.state->'participants') participant
      WHERE lower(participant->>'id')=ANY($1::text[]))`, [ids])).rowCount) || 0
  // Other participants' receipts referencing those agreements cascade in PostgreSQL.
  return { agreements, operations }
}

module.exports = { purgeChatAgreementsForAccounts }
