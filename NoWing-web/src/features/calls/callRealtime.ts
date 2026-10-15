import type { SupabaseClient } from '@supabase/supabase-js'
import { postCallData } from './callControl'

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
  action: 'accept' | 'decline' | 'cancel' | 'end' | 'busy' | 'missed' | 'collision'
  fromUserId: string
}

async function optionalBroadcast(client: SupabaseClient | null, targetUserId: string, event: string, payload: Record<string, unknown>) {
  if (!client) return
  const channel = client.channel(`call-user:${targetUserId.trim()}`)
  try {
    await new Promise<void>((resolve) => {
      const timeout = setTimeout(resolve, 2500)
      channel.subscribe((status) => {
        if (status !== 'SUBSCRIBED') return
        void channel.send({ type: 'broadcast', event, payload })
          .catch(() => undefined)
          .finally(() => { clearTimeout(timeout); resolve() })
      })
    })
  } finally {
    void client.removeChannel(channel)
  }
}

export async function sendCallInvite(client: SupabaseClient | null, targetUserId: string, invite: CallInvitePayload, accessToken: string) {
  const target = targetUserId.trim()
  if (!target) throw new Error('Falta el usuario destino para la llamada.')
  // The API checks the persisted call, the authenticated owner, and the invited recipient.
  await postCallData('/v1/data/notifications', {
    recipientId: target,
    actorId: invite.callerUserId,
    type: 'call_invite',
    targetId: invite.callId,
    content: invite.isVideo ? 'Videollamada entrante' : 'Llamada entrante',
    extraData: {
      call_id: invite.callId,
      call_context_type: invite.contextType,
      call_context_id: invite.contextId ?? '',
      call_title: invite.title,
      call_created_at: new Date().toISOString(),
      call_is_video: invite.isVideo,
    },
  }, accessToken)
  await optionalBroadcast(client, target, 'call_invite', { ...invite })
}

export async function sendCallResponse(client: SupabaseClient | null, targetUserId: string, response: CallResponsePayload, accessToken: string) {
  const target = targetUserId.trim()
  if (!target) return
  // Terminal alerts follow leave; accepted alerts follow accept.
  await postCallData('/v1/data/notifications', {
    recipientId: target,
    actorId: response.fromUserId,
    type: response.action === 'accept' ? 'call_response' : 'call_cancel',
    targetId: response.callId,
    content: response.action === 'accept' ? 'Llamada aceptada' : 'Llamada finalizada',
    extraData: { call_id: response.callId, call_action: response.action, from_user_id: response.fromUserId },
  }, accessToken, response.action !== 'accept')
  await optionalBroadcast(client, target, 'call_response', { ...response })
}
