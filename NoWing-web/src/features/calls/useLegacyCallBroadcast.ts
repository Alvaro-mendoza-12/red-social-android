import { useEffect } from 'react'
import type { SupabaseClient } from '@supabase/supabase-js'
import { removeRealtimeChannel, subscribeRealtimeChannel } from '../../core/supabase'
import type { CallInvitePayload } from './callRealtime'

type Options = {
 client: SupabaseClient | null
 userId: string
 isFallbackSession: boolean
 incomingCallId?: string
 presentInvite: (invite: CallInvitePayload & { receivedAt: number }) => void
 dismissInvite: (callId: string) => void
 clearOnSignOut: () => void
}

export function useLegacyCallBroadcast({ client, userId, isFallbackSession, incomingCallId, presentInvite, dismissInvite, clearOnSignOut }: Options) {
 useEffect(() => {
  // A missing legacy client says nothing about a valid Ubuntu call. Clearing
  // here on every incomingCallId change erased real incoming calls immediately.
  if (!userId) { clearOnSignOut(); return }
  if (isFallbackSession || !client) return
  const channel = client.channel(`call-user:${userId}`)
   .on('broadcast', { event: 'call_invite' }, event => {
    const payload = (event.payload ?? {}) as Partial<CallInvitePayload>
    const callId = String(payload.callId ?? '').trim(), callerUserId = String(payload.callerUserId ?? '').trim()
    if (!callId || !callerUserId || callerUserId === userId) return
    presentInvite({callId,callerUserId,title:String(payload.title ?? '').trim() || 'Llamada',callerName:String(payload.callerName ?? '').trim() || 'Usuario',callerAvatarUrl:String(payload.callerAvatarUrl ?? '').trim(),contextType:String(payload.contextType ?? '').trim() || 'dm',contextId:String(payload.contextId ?? '').trim(),isVideo:Boolean(payload.isVideo),receivedAt:Date.now()})
   })
   .on('broadcast', { event: 'call_response' }, event => {
    const payload = (event.payload ?? {}) as {callId?:string}
    if (incomingCallId && payload.callId === incomingCallId) dismissInvite(incomingCallId)
   })
  const subscribed = subscribeRealtimeChannel(channel, {reason:'web-call-user'})
  return () => { void removeRealtimeChannel(subscribed ?? channel) }
 }, [client, userId, isFallbackSession, incomingCallId, presentInvite, dismissInvite, clearOnSignOut])
}
