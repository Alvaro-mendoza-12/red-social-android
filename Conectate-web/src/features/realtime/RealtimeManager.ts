import { removeRealtimeChannel, subscribeRealtimeChannel, supabase } from '../../core/supabase'

export interface RealtimeEvent {
  type: 'message' | 'user_status' | 'typing' | 'presence' | 'notification'
  data: any
  timestamp: number
  from?: string
  to?: string
  roomId?: string
}

export interface RealtimeSubscription {
  id: string
  event: string
  callback: (event: RealtimeEvent) => void
  unsubscribe: () => void
}

export class RealtimeManager {
  private subscriptions: Map<string, RealtimeSubscription> = new Map()
  private channels: Map<string, any> = new Map()
  private reconnectAttempts: number = 0
  private maxReconnectAttempts: number = 5
  private reconnectDelay: number = 1000

  constructor() {
    this.setupConnectionListeners()
  }

  private setupConnectionListeners(): void {
    if (typeof window !== 'undefined') {
      window.addEventListener('online', () => {
        console.log('Connection restored, reconnecting WebSocket...')
        this.reconnectAll()
      })

      window.addEventListener('offline', () => {
        console.log('Connection lost, WebSocket will reconnect when online')
      })
    }
  }

  subscribe(eventType: string, callback: (event: RealtimeEvent) => void): RealtimeSubscription {
    const id = crypto.randomUUID()
    const subscription: RealtimeSubscription = {
      id,
      event: eventType,
      callback,
      unsubscribe: () => this.unsubscribe(id)
    }

    this.subscriptions.set(id, subscription)
    
    // Setup Supabase real-time subscription
    this.setupSupabaseSubscription(eventType, callback)
    
    return subscription
  }

  private setupSupabaseSubscription(eventType: string, callback: (event: RealtimeEvent) => void): void {
    if (!supabase) return

    switch (eventType) {
      case 'message':
        this.subscribeToMessages(callback)
        break
      case 'user_status':
        this.subscribeToUserStatus(callback)
        break
      case 'typing':
        this.subscribeToTyping(callback)
        break
      case 'presence':
        this.subscribeToPresence(callback)
        break
      case 'notification':
        this.subscribeToNotifications(callback)
        break
    }
  }

  private subscribeToMessages(callback: (event: RealtimeEvent) => void): void {
    if (!supabase) return

    const channel = supabase
      .channel('global-messages')
      .on('postgres_changes', { 
        event: '*', 
        schema: 'public', 
        table: 'messages' 
      }, (payload) => {
        const event: RealtimeEvent = {
          type: 'message',
          data: payload,
          timestamp: Date.now(),
          from: payload.new?.sender_id,
          to: payload.new?.chat_id
        }
        callback(event)
      })

    const subscribed = subscribeRealtimeChannel(channel, {
      reason: 'messages',
      onStatus: (status, error) => {
        if (status === 'SUBSCRIBED') {
          console.log('Subscribed to messages channel')
          return
        }
        if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED') {
          console.error('Error subscribing to messages channel', error)
          this.handleSubscriptionError('messages')
        }
      },
    })

    if (subscribed) {
      this.channels.set('messages', subscribed)
    }
  }

  private subscribeToUserStatus(callback: (event: RealtimeEvent) => void): void {
    if (!supabase) return

    const channel = supabase
      .channel('user-status')
      .on('postgres_changes', { 
        event: '*', 
        schema: 'public', 
        table: 'profiles' 
      }, (payload) => {
        const event: RealtimeEvent = {
          type: 'user_status',
          data: {
            userId: payload.new?.id,
            status: payload.new?.is_online ? 'online' : 'offline',
            lastSeen: payload.new?.last_seen
          },
          timestamp: Date.now()
        }
        callback(event)
      })

    const subscribed = subscribeRealtimeChannel(channel, {
      reason: 'user-status',
      onStatus: (status, error) => {
        if (status === 'SUBSCRIBED') {
          console.log('Subscribed to user status channel')
          return
        }
        if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED') {
          console.error('Error subscribing to user status channel', error)
          this.handleSubscriptionError('user-status')
        }
      },
    })

    if (subscribed) {
      this.channels.set('user-status', subscribed)
    }
  }

  private subscribeToTyping(callback: (event: RealtimeEvent) => void): void {
    if (!supabase) return

    // This would typically use a broadcast channel for typing indicators
    const channel = supabase.channel('typing-indicators')
    
    channel.on('broadcast', { event: 'typing' }, (payload) => {
      const event: RealtimeEvent = {
        type: 'typing',
        data: payload,
        timestamp: Date.now(),
        from: payload.userId,
        roomId: payload.roomId
      }
      callback(event)
    })

    const subscribed = subscribeRealtimeChannel(channel, {
      reason: 'typing',
      onStatus: (status, error) => {
        if (status === 'SUBSCRIBED') {
          console.log('Subscribed to typing indicators channel')
          return
        }
        if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED') {
          console.error('Error subscribing to typing indicators channel', error)
          this.handleSubscriptionError('typing')
        }
      },
    })

    if (subscribed) {
      this.channels.set('typing', subscribed)
    }
  }

  private subscribeToPresence(callback: (event: RealtimeEvent) => void): void {
    if (!supabase) return

    const channel = supabase
      .channel('presence')
      .on('presence', { event: 'sync' }, (payload) => {
        const event: RealtimeEvent = {
          type: 'presence',
          data: {
            userIds: payload.presences?.map((p: any) => p.user_id) || [],
            timestamp: payload.timestamp
          },
          timestamp: Date.now()
        }
        callback(event)
      })

    const subscribed = subscribeRealtimeChannel(channel, {
      reason: 'presence',
      onStatus: (status, error) => {
        if (status === 'SUBSCRIBED') {
          console.log('Subscribed to presence channel')
          return
        }
        if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED') {
          console.error('Error subscribing to presence channel', error)
          this.handleSubscriptionError('presence')
        }
      },
    })

    if (subscribed) {
      this.channels.set('presence', subscribed)
    }
  }

  private subscribeToNotifications(callback: (event: RealtimeEvent) => void): void {
    if (!supabase) return

    const channel = supabase
      .channel('notifications')
      .on('postgres_changes', { 
        event: 'INSERT', 
        schema: 'public', 
        table: 'notifications' 
      }, (payload) => {
        const event: RealtimeEvent = {
          type: 'notification',
          data: payload.new,
          timestamp: Date.now(),
          to: payload.new?.user_id
        }
        callback(event)
      })

    const subscribed = subscribeRealtimeChannel(channel, {
      reason: 'notifications',
      onStatus: (status, error) => {
        if (status === 'SUBSCRIBED') {
          console.log('Subscribed to notifications channel')
          return
        }
        if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED') {
          console.error('Error subscribing to notifications channel', error)
          this.handleSubscriptionError('notifications')
        }
      },
    })

    if (subscribed) {
      this.channels.set('notifications', subscribed)
    }
  }

  unsubscribe(subscriptionId: string): void {
    const subscription = this.subscriptions.get(subscriptionId)
    if (subscription) {
      this.subscriptions.delete(subscriptionId)
    }
  }

  private handleSubscriptionError(channelKey: string): void {
    console.error(`Error in ${channelKey} channel, attempting to reconnect...`)
    
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      setTimeout(() => {
        this.reconnectAttempts++
        this.reconnectChannel(channelKey)
      }, this.reconnectDelay * this.reconnectAttempts)
    }
  }

  private reconnectChannel(channelKey: string): void {
    const channel = this.channels.get(channelKey)
    if (channel) {
      try {
        void removeRealtimeChannel(channel)
        this.channels.delete(channelKey)
        
        // Resubscribe after a delay
        setTimeout(() => {
          // Re-setup the subscription
          const subscription = Array.from(this.subscriptions.values())
            .find(sub => sub.event === channelKey)
          
          if (subscription) {
            this.setupSupabaseSubscription(subscription.event, subscription.callback)
          }
        }, 1000)
      } catch (error) {
        console.error(`Error reconnecting ${channelKey} channel:`, error)
      }
    }
  }

  private reconnectAll(): void {
    this.reconnectAttempts = 0
    
    Array.from(this.channels.entries()).forEach(([key, channel]) => {
      try {
        void removeRealtimeChannel(channel)
        this.channels.delete(key)
      } catch (error) {
        console.error(`Error unsubscribing from ${key} channel:`, error)
      }
    })

    // Resubscribe to all active subscriptions
    setTimeout(() => {
      Array.from(this.subscriptions.values()).forEach(subscription => {
        this.setupSupabaseSubscription(subscription.event, subscription.callback)
      })
    }, 1000)
  }

  broadcastTyping(roomId: string, isTyping: boolean): void {
    if (!supabase) return

    const channel = this.channels.get('typing')
    if (channel) {
      channel.send({
        type: 'broadcast',
        event: 'typing',
        payload: {
          userId: this.getCurrentUserId(),
          roomId,
          isTyping,
          timestamp: Date.now()
        }
      })
    }
  }

  updatePresence(status: 'online' | 'away' | 'busy'): void {
    if (!supabase) return

    const channel = this.channels.get('presence')
    if (channel) {
      channel.track({
        user_id: this.getCurrentUserId(),
        status,
        timestamp: Date.now()
      })
    }
  }

  private getCurrentUserId(): string {
    // In a real implementation, this would come from auth context
    return 'current-user-id'
  }

  getConnectionStatus(): {
    connected: boolean
    channels: string[]
    subscriptions: number
  } {
    return {
      connected: this.reconnectAttempts === 0,
      channels: Array.from(this.channels.keys()),
      subscriptions: this.subscriptions.size
    }
  }

  cleanup(): void {
    // Unsubscribe from all channels
    Array.from(this.channels.entries()).forEach(([key, channel]) => {
      try {
        void removeRealtimeChannel(channel)
      } catch (error) {
        console.error(`Error unsubscribing from ${key} channel:`, error)
      }
    })

    // Clear all references
    this.channels.clear()
    this.subscriptions.clear()
    this.reconnectAttempts = 0
  }
}

export default RealtimeManager
