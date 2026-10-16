import { act, renderHook } from '@testing-library/react'
import type { SupabaseClient } from '@supabase/supabase-js'
import { useLegacyCallBroadcast } from '../../features/calls/useLegacyCallBroadcast'
import { removeRealtimeChannel, subscribeRealtimeChannel } from '../../core/supabase'
jest.mock('../../core/supabase', () => ({ subscribeRealtimeChannel:jest.fn(channel=>channel),removeRealtimeChannel:jest.fn(async()=>{}) }))
const options=()=>({client:null as SupabaseClient|null,userId:'receiver',isFallbackSession:true,incomingCallId:undefined as string|undefined,presentInvite:jest.fn(),dismissInvite:jest.fn(),clearOnSignOut:jest.fn()})
beforeEach(()=>jest.clearAllMocks())
test('a real Ubuntu invite survives when the legacy client is absent',()=>{
 const value=options(),view=renderHook(props=>useLegacyCallBroadcast(props),{initialProps:value})
 view.rerender({...value,incomingCallId:'server-validated-call'})
 expect(value.clearOnSignOut).not.toHaveBeenCalled();expect(subscribeRealtimeChannel).not.toHaveBeenCalled()
})
test('fallback accounts also ignore a configured legacy client',()=>{
 const value=options();value.client={channel:jest.fn()} as unknown as SupabaseClient
 renderHook(()=>useLegacyCallBroadcast(value));expect(value.client.channel).not.toHaveBeenCalled();expect(value.clearOnSignOut).not.toHaveBeenCalled()
})
test('sign-out clears the pending invite',()=>{
 const value=options(),view=renderHook(props=>useLegacyCallBroadcast(props),{initialProps:value})
 view.rerender({...value,userId:''});expect(value.clearOnSignOut).toHaveBeenCalledTimes(1)
})
test('legacy subscriptions validate invite identities and dismiss only the active call',()=>{
 const value={...options(),isFallbackSession:false,incomingCallId:'active-call'},handlers:Record<string,(event:{payload:Record<string,unknown>})=>void>={}
 const channel={on:jest.fn((_type:string,filter:{event:string},handler:typeof handlers[string])=>{handlers[filter.event]=handler;return channel})}
 value.client={channel:jest.fn(()=>channel)} as unknown as SupabaseClient
 const view=renderHook(()=>useLegacyCallBroadcast(value))
 act(()=>handlers.call_invite({payload:{callId:'new-call',callerUserId:'caller',isVideo:true}}));expect(value.presentInvite).toHaveBeenCalledWith(expect.objectContaining({callId:'new-call',callerUserId:'caller',isVideo:true}))
 act(()=>handlers.call_invite({payload:{callId:'self-call',callerUserId:'receiver'}}));expect(value.presentInvite).toHaveBeenCalledTimes(1)
 act(()=>handlers.call_response({payload:{callId:'other'}}));expect(value.dismissInvite).not.toHaveBeenCalled()
 act(()=>handlers.call_response({payload:{callId:'active-call'}}));expect(value.dismissInvite).toHaveBeenCalledWith('active-call')
 view.unmount();expect(removeRealtimeChannel).toHaveBeenCalledWith(channel)
})
