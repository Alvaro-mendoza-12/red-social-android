import { supabase } from '../../core/supabase'
import { textOf, type GenericRow } from '../../core/utils'

export async function getOrCreateDirectChat(currentUserId: string, otherUserId: string): Promise<string> {
  if (!supabase) throw new Error('SUPABASE_NOT_CONFIGURED')
  if (!currentUserId || !otherUserId) throw new Error('CHAT_PARTICIPANT_REQUIRED')

  const client = supabase

  const { data: existingRows, error: existingError } = await client
    .from('chats')
    .select('*')
    .eq('is_group', false)
    .contains('participants', [currentUserId])
    .contains('participants', [otherUserId])
    .order('updated_at', { ascending: false })
    .limit(1)
  if (existingError) throw existingError

  const existing = ((existingRows ?? []) as GenericRow[])[0]
  const existingId = textOf(existing ?? {}, ['id'], '')
  if (existingId) {
    const nowIso = new Date().toISOString()
    await client.from('chat_participants').upsert(
      [
        {
          chat_id: existingId,
          user_id: currentUserId,
          joined_at: nowIso,
          last_read_at: nowIso,
        },
        {
          chat_id: existingId,
          user_id: otherUserId,
          joined_at: nowIso,
          last_read_at: nowIso,
        },
      ],
      { onConflict: 'chat_id,user_id' },
    )
    return existingId
  }

  const nowIso = new Date().toISOString()
  const { data: insertedRows, error: createError } = await client
    .from('chats')
    .insert({
      participants: [currentUserId, otherUserId],
      is_group: false,
      created_at: nowIso,
      updated_at: nowIso,
      last_message: null,
      last_message_at: null,
      last_message_sender_id: null,
      last_message_id: null,
    })
    .select('*')
    .limit(1)
  if (createError) throw createError

  const created = ((insertedRows ?? []) as GenericRow[])[0]
  const createdId = textOf(created ?? {}, ['id'], '')
  if (!createdId) throw new Error('CHAT_CREATE_FAILED')

  await client.from('chat_participants').upsert(
    [
      {
        chat_id: createdId,
        user_id: currentUserId,
        joined_at: nowIso,
        last_read_at: nowIso,
      },
      {
        chat_id: createdId,
        user_id: otherUserId,
        joined_at: nowIso,
        last_read_at: nowIso,
      },
    ],
    { onConflict: 'chat_id,user_id' },
  )

  return createdId
}

export async function sendDirectMessage(
  chatId: string,
  senderUserId: string,
  content: string,
  contentType = 'text',
  mediaUrl: string | null = null,
): Promise<void> {
  if (!supabase) throw new Error('SUPABASE_NOT_CONFIGURED')
  if (!chatId) throw new Error('CHAT_REQUIRED')
  if (!content.trim() && !mediaUrl) throw new Error('MESSAGE_EMPTY')
  const client = supabase

  const { error: rpcError } = await client.rpc('secure_send_message', {
    p_chat_id: chatId,
    p_content: content,
    p_content_type: contentType,
    p_media_url: mediaUrl,
    p_media_size: 0,
  })
  if (!rpcError) return

  const nowIso = new Date().toISOString()
  const { error: fallbackError } = await client.from('messages').insert({
    chat_id: chatId,
    sender_id: senderUserId,
    content,
    content_type: contentType,
    media_url: mediaUrl,
    status: 'sent',
    created_at: nowIso,
    updated_at: nowIso,
  })
  if (fallbackError) throw fallbackError

  await client
    .from('chats')
    .update({
      last_message: content || 'Archivo',
      last_message_at: nowIso,
      last_message_sender_id: senderUserId,
      updated_at: nowIso,
    })
    .eq('id', chatId)
}
