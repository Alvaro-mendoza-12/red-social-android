import type { SupabaseClient } from '@supabase/supabase-js'

export type CallInvitePayload = {
  callId: string
  title: string
  callerUserId: string
  callerName: string
  callerAvatarUrl?: string
  contextType: string
  contextId?: string
  isVideo: boolean
}

export type CallResponsePayload = {
  callId: string
  action: 'accept' | 'decline' | 'cancel' | 'end'
  fromUserId: string
}

function callUserChannel(userId: string): string {
  return `call-user:${userId.trim()}`
}

async function sendBroadcast(
  client: SupabaseClient,
  channelName: string,
  event: string,
  payload: Record<string, unknown>,
) {
  const channel = client.channel(channelName)

  await new Promise<void>((resolve, reject) => {
    const timeout = window.setTimeout(() => {
      reject(new Error('No se pudo abrir el canal de llamada en tiempo real.'))
    }, 6000)

    channel.subscribe((status) => {
      if (status !== 'SUBSCRIBED') return
      window.clearTimeout(timeout)
      void channel
        .send({
          type: 'broadcast',
          event,
          payload,
        })
        .then(() => resolve())
        .catch(reject)
    })
  }).finally(() => {
    void client.removeChannel(channel)
  })
}

export async function sendCallInvite(
  client: SupabaseClient,
  targetUserId: string,
  invite: CallInvitePayload,
) {
  const cleanTarget = targetUserId.trim()
  if (!cleanTarget) throw new Error('Falta el usuario destino para la llamada.')

  await sendBroadcast(client, callUserChannel(cleanTarget), 'call_invite', {
    callId: invite.callId,
    title: invite.title,
    callerUserId: invite.callerUserId,
    callerName: invite.callerName,
    callerAvatarUrl: invite.callerAvatarUrl ?? '',
    contextType: invite.contextType,
    contextId: invite.contextId ?? '',
    isVideo: invite.isVideo,
  })

  const { error } = await client.from('notifications').insert({
    recipient_id: cleanTarget,
    actor_id: invite.callerUserId,
    type: 'call_invite',
    target_id: invite.callId,
    content: invite.isVideo ? 'Videollamada entrante' : 'Llamada entrante',
    extra_data: {
      call_id: invite.callId,
      call_context_type: invite.contextType,
      call_context_id: invite.contextId ?? '',
      call_title: invite.title,
      call_created_at: new Date().toISOString(),
      call_is_video: invite.isVideo,
    },
  })

  if (error) {
    console.warn('No se pudo guardar notificación de llamada', error)
  }
}

export async function sendCallResponse(
  client: SupabaseClient,
  targetUserId: string,
  response: CallResponsePayload,
) {
  const cleanTarget = targetUserId.trim()
  if (!cleanTarget) return
  await sendBroadcast(client, callUserChannel(cleanTarget), 'call_response', {
    callId: response.callId,
    action: response.action,
    fromUserId: response.fromUserId,
  })
}
