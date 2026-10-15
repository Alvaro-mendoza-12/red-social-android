import { isFallbackAuthConfigured } from './auth/fallbackAuth'
import { isSupabaseConfigured, subscribeRealtimeChannel } from './supabase'

type RealtimeChannelLike = {
  subscribe: (callback?: (status: string, error?: unknown) => void) => unknown
  unsubscribe?: () => unknown
}
import { isUbuntuPrimary } from './ubuntuBackend'
import { subscribeUbuntuRealtime, type UbuntuRealtimeEvent } from './ubuntuRealtime'

export function isUbuntuRealtimeEnabled(): boolean {
  return isUbuntuPrimary || (isFallbackAuthConfigured && !isSupabaseConfigured)
}

export function subscribeAppRealtime(input: {
  ubuntuChannel: string
  onUbuntuEvent?: (event: UbuntuRealtimeEvent) => void
  supabaseChannel?: RealtimeChannelLike | null
  reason?: string
}): () => void {
  if (isUbuntuRealtimeEnabled() && input.ubuntuChannel) {
    return subscribeUbuntuRealtime({
      channel: input.ubuntuChannel,
      onEvent: (event) => input.onUbuntuEvent?.(event),
    })
  }

  if (input.supabaseChannel) {
    subscribeRealtimeChannel(input.supabaseChannel, { reason: input.reason })
    return () => {
      void input.supabaseChannel?.unsubscribe?.()
    }
  }

  return () => {}
}
