import { createClient, SupabaseClient } from '@supabase/supabase-js'
import { QueryClient, useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { isFallbackAuthConfigured } from '../auth/fallbackAuth'

/**
 * Cliente opcional legacy. Con Ubuntu como backend principal, supabase queda en null.
 */

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL?.trim()
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY?.trim()

export const supabase: SupabaseClient | null =
  !isFallbackAuthConfigured && supabaseUrl && supabaseAnonKey
    ? createClient(supabaseUrl, supabaseAnonKey, {
        auth: {
          persistSession: true,
          autoRefreshToken: true,
          detectSessionInUrl: true,
        },
        realtime: {
          params: {
            eventsPerSecond: 10,
          },
        },
        global: {
          headers: {
            'X-Client-Info': 'conectate-web/2.0.0',
          },
        },
      })
    : null

// 🔥 QUERY CLIENT ENTERPRISE
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 minutos
      cacheTime: 1000 * 60 * 10, // 10 minutos
      retry: 3,
      retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 30000),
      refetchOnWindowFocus: false,
      refetchOnReconnect: true
    },
    mutations: {
      retry: 1,
      retryDelay: 1000
    }
  }
})

// 🔥 TIPOS DE DATOS
export interface User {
  id: string
  username: string
  email: string
  avatar_url?: string
  display_name?: string
  bio?: string
  verified: boolean
  created_at: string
  updated_at: string
  followers_count: number
  following_count: number
  posts_count: number
}

export interface Post {
  id: string
  user_id: string
  content: string
  media_url?: string
  media_type?: 'image' | 'video' | 'reel' | 'voice' | 'disco'
  thumbnail_url?: string
  likes_count: number
  comments_count: number
  shares_count: number
  views_count: number
  created_at: string
  updated_at: string
  user?: User
  is_liked?: boolean
  is_bookmarked?: boolean
}

export interface Message {
  id: string
  chat_id: string
  sender_id: string
  content: string
  media_url?: string
  media_type?: 'image' | 'video' | 'audio'
  reply_to_id?: string
  status: 'sending' | 'sent' | 'delivered' | 'read'
  created_at: string
  updated_at: string
  sender?: User
}

export interface Chat {
  id: string
  name?: string
  is_group: boolean
  participants: User[]
  last_message?: Message
  created_at: string
  updated_at: string
  unread_count: number
}

export interface VoiceRoom {
  id: string
  title: string
  host_id: string
  max_participants: number
  current_participants: number
  is_live: boolean
  category: string
  created_at: string
  updated_at: string
  host?: User
  participants?: User[]
}

export interface DiscoRoom {
  id: string
  name: string
  dj_id: string
  current_track_title?: string
  current_track_artist?: string
  current_track_duration?: number
  current_track_position?: number
  listeners_count: number
  genre: string
  is_live: boolean
  created_at: string
  updated_at: string
  dj?: User
}

export interface Notification {
  id: string
  user_id: string
  type: 'like' | 'comment' | 'follow' | 'mention' | 'message' | 'voice_invite' | 'disco_invite'
  title: string
  body: string
  data?: Record<string, any>
  read: boolean
  created_at: string
  related_user?: User
}

// 🔥 API HOOKS ENTERPRISE

// 🔥 AUTHENTICATION
export const useCurrentUser = () => {
  return useQuery({
    queryKey: ['current-user'],
    queryFn: async () => {
      const { data: { user } } = await supabase.auth.getUser()
      if (!user) throw new Error('No authenticated user')
      
      const { data, error } = await supabase
        .from('users')
        .select('*')
        .eq('id', user.id)
        .single()
      
      if (error) throw error
      return data as User
    },
    staleTime: 1000 * 60 * 10, // 10 minutos
    enabled: !!supabase.auth.getUser()
  })
}

export const useSignIn = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async ({ email, password }: { email: string; password: string }) => {
      const { data, error } = await supabase.auth.signInWithPassword({
        email,
        password
      })
      
      if (error) throw error
      return data
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['current-user'] })
    }
  })
}

export const useSignUp = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async ({ 
      email, 
      password, 
      username, 
      displayName 
    }: { 
      email: string; 
      password: string; 
      username: string; 
      displayName: string 
    }) => {
      // 1. Sign up
      const { data, error } = await supabase.auth.signUp({
        email,
        password
      })
      
      if (error) throw error
      
      // 2. Create user profile
      if (data.user) {
        const { error: profileError } = await supabase
          .from('users')
          .insert({
            id: data.user.id,
            username,
            display_name: displayName,
            email,
            verified: false
          })
        
        if (profileError) throw profileError
      }
      
      return data
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['current-user'] })
    }
  })
}

export const useSignOut = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async () => {
      const { error } = await supabase.auth.signOut()
      if (error) throw error
    },
    onSuccess: () => {
      queryClient.clear()
    }
  })
}

// 🔥 POSTS
export const usePosts = (limit = 20, offset = 0) => {
  return useQuery({
    queryKey: ['posts', limit, offset],
    queryFn: async () => {
      const { data, error } = await supabase
        .from('posts')
        .select(`
          *,
          user:users(*)
        `)
        .order('created_at', { ascending: false })
        .range(offset, offset + limit - 1)
      
      if (error) throw error
      return data as Post[]
    },
    staleTime: 1000 * 60 * 2 // 2 minutos
  })
}

export const useCreatePost = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async ({ 
      content, 
      mediaUrl, 
      mediaType 
    }: { 
      content: string; 
      mediaUrl?: string; 
      mediaType?: string 
    }) => {
      const { data: { user } } = await supabase.auth.getUser()
      if (!user) throw new Error('Not authenticated')
      
      const { data, error } = await supabase
        .from('posts')
        .insert({
          user_id: user.id,
          content,
          media_url: mediaUrl,
          media_type: mediaType
        })
        .select()
        .single()
      
      if (error) throw error
      return data as Post
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['posts'] })
      queryClient.invalidateQueries({ queryKey: ['current-user'] })
    }
  })
}

export const useLikePost = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async ({ postId, isLiked }: { postId: string; isLiked: boolean }) => {
      if (isLiked) {
        const { error } = await supabase
          .from('likes')
          .delete()
          .eq('post_id', postId)
        
        if (error) throw error
      } else {
        const { data: { user } } = await supabase.auth.getUser()
        if (!user) throw new Error('Not authenticated')
        
        const { error } = await supabase
          .from('likes')
          .insert({
            post_id: postId,
            user_id: user.id
          })
        
        if (error) throw error
      }
      
      return !isLiked
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['posts'] })
    }
  })
}

export const useBookmarkPost = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async ({ postId, isBookmarked }: { postId: string; isBookmarked: boolean }) => {
      if (isBookmarked) {
        const { error } = await supabase
          .from('bookmarks')
          .delete()
          .eq('post_id', postId)
        
        if (error) throw error
      } else {
        const { data: { user } } = await supabase.auth.getUser()
        if (!user) throw new Error('Not authenticated')
        
        const { error } = await supabase
          .from('bookmarks')
          .insert({
            post_id: postId,
            user_id: user.id
          })
        
        if (error) throw error
      }
      
      return !isBookmarked
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['posts'] })
    }
  })
}

// 🔥 MESSAGES
export const useChats = () => {
  return useQuery({
    queryKey: ['chats'],
    queryFn: async () => {
      const { data: { user } } = await supabase.auth.getUser()
      if (!user) throw new Error('Not authenticated')
      
      const { data, error } = await supabase
        .from('chat_participants')
        .select(`
          chat:chats(
            *,
            last_message:messages(*),
            participants:chat_participants(
              user:users(*)
            )
          )
        `)
        .eq('user_id', user.id)
      
      if (error) throw error
      
      return data?.map((item: any) => ({
        ...item.chat,
        unread_count: 0 // Calcular unread_count
      })) as Chat[]
    },
    staleTime: 1000 * 60 * 1 // 1 minuto
  })
}

export const useMessages = (chatId: string) => {
  return useQuery({
    queryKey: ['messages', chatId],
    queryFn: async () => {
      const { data, error } = await supabase
        .from('messages')
        .select(`
          *,
          sender:users(*)
        `)
        .eq('chat_id', chatId)
        .order('created_at', { ascending: true })
      
      if (error) throw error
      return data as Message[]
    },
    staleTime: 1000 * 60 * 1 // 1 minuto
  })
}

export const useSendMessage = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async ({ 
      chatId, 
      content, 
      mediaUrl, 
      mediaType, 
      replyToId 
    }: { 
      chatId: string; 
      content: string; 
      mediaUrl?: string; 
      mediaType?: string; 
      replyToId?: string 
    }) => {
      const { data: { user } } = await supabase.auth.getUser()
      if (!user) throw new Error('Not authenticated')
      
      const { data, error } = await supabase
        .from('messages')
        .insert({
          chat_id: chatId,
          sender_id: user.id,
          content,
          media_url: mediaUrl,
          media_type: mediaType,
          reply_to_id: replyToId,
          status: 'sent'
        })
        .select(`
          *,
          sender:users(*)
        `)
        .single()
      
      if (error) throw error
      return data as Message
    },
    onSuccess: (data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['messages', variables.chatId] })
      queryClient.invalidateQueries({ queryKey: ['chats'] })
    }
  })
}

// 🔥 VOICE ROOMS
export const useVoiceRooms = () => {
  return useQuery({
    queryKey: ['voice-rooms'],
    queryFn: async () => {
      const { data, error } = await supabase
        .from('voice_rooms')
        .select(`
          *,
          host:users(*),
          participants:voice_room_participants(
            user:users(*)
          )
        `)
        .eq('is_live', true)
        .order('created_at', { ascending: false })
      
      if (error) throw error
      return data as VoiceRoom[]
    },
    staleTime: 1000 * 60 * 1 // 1 minuto
  })
}

export const useJoinVoiceRoom = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async ({ roomId }: { roomId: string }) => {
      const { data: { user } } = await supabase.auth.getUser()
      if (!user) throw new Error('Not authenticated')
      
      const { data, error } = await supabase
        .from('voice_room_participants')
        .insert({
          voice_room_id: roomId,
          user_id: user.id,
          joined_at: new Date().toISOString()
        })
        .select()
        .single()
      
      if (error) throw error
      return data
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['voice-rooms'] })
    }
  })
}

// 🔥 DISCO ROOMS
export const useDiscoRooms = () => {
  return useQuery({
    queryKey: ['disco-rooms'],
    queryFn: async () => {
      const { data, error } = await supabase
        .from('disco_rooms')
        .select(`
          *,
          dj:users(*)
        `)
        .eq('is_live', true)
        .order('created_at', { ascending: false })
      
      if (error) throw error
      return data as DiscoRoom[]
    },
    staleTime: 1000 * 60 * 1 // 1 minuto
  })
}

export const useJoinDiscoRoom = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async ({ roomId }: { roomId: string }) => {
      const { data: { user } } = await supabase.auth.getUser()
      if (!user) throw new Error('Not authenticated')
      
      const { data, error } = await supabase
        .from('disco_room_listeners')
        .insert({
          disco_room_id: roomId,
          user_id: user.id,
          joined_at: new Date().toISOString()
        })
        .select()
        .single()
      
      if (error) throw error
      return data
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['disco-rooms'] })
    }
  })
}

// 🔥 NOTIFICATIONS
export const useNotifications = () => {
  return useQuery({
    queryKey: ['notifications'],
    queryFn: async () => {
      const { data: { user } } = await supabase.auth.getUser()
      if (!user) throw new Error('Not authenticated')
      
      const { data, error } = await supabase
        .from('notifications')
        .select(`
          *,
          related_user:users(*)
        `)
        .eq('user_id', user.id)
        .order('created_at', { ascending: false })
        .limit(50)
      
      if (error) throw error
      return data as Notification[]
    },
    staleTime: 1000 * 60 * 2 // 2 minutos
  })
}

export const useMarkNotificationAsRead = () => {
  const queryClient = useQueryClient()
  
  return useMutation({
    mutationFn: async ({ notificationId }: { notificationId: string }) => {
      const { error } = await supabase
        .from('notifications')
        .update({ read: true })
        .eq('id', notificationId)
      
      if (error) throw error
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications'] })
    }
  })
}

// 🔥 SEARCH
export const useSearchUsers = (query: string) => {
  return useQuery({
    queryKey: ['search-users', query],
    queryFn: async () => {
      if (!query.trim()) return []
      
      const { data, error } = await supabase
        .from('users')
        .select('*')
        .ilike('username', `%${query}%`)
        .limit(20)
      
      if (error) throw error
      return data as User[]
    },
    enabled: query.trim().length > 0,
    staleTime: 1000 * 60 * 5 // 5 minutos
  })
}

export const useSearchPosts = (query: string) => {
  return useQuery({
    queryKey: ['search-posts', query],
    queryFn: async () => {
      if (!query.trim()) return []
      
      const { data, error } = await supabase
        .from('posts')
        .select(`
          *,
          user:users(*)
        `)
        .ilike('content', `%${query}%`)
        .order('created_at', { ascending: false })
        .limit(50)
      
      if (error) throw error
      return data as Post[]
    },
    enabled: query.trim().length > 0,
    staleTime: 1000 * 60 * 5 // 5 minutos
  })
}

// 🔥 UTILIDADES
export const useRealtimeSubscription = (channel: string, event: string, callback: (payload: any) => void) => {
  useEffect(() => {
    const subscription = supabase
      .channel(channel)
      .on('postgres_changes', { event, schema: 'public', table: channel }, callback)
      .subscribe()
    
    return () => {
      subscription.unsubscribe()
    }
  }, [channel, event, callback])
}

// 🔥 ERROR HANDLING
export class ApiError extends Error {
  constructor(
    message: string,
    public code?: string,
    public statusCode?: number
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

// 🔥 RETRY CONFIGURATION
export const retryConfig = {
  retries: 3,
  retryDelay: (attemptIndex: number) => Math.min(1000 * 2 ** attemptIndex, 30000),
  retryCondition: (error: any) => {
    // No reintentar en errores de autenticación
    if (error?.message?.includes('JWT') || error?.message?.includes('Unauthorized')) {
      return false
    }
    
    // Reintentar en errores de red
    if (error?.message?.includes('Network') || error?.message?.includes('fetch')) {
      return true
    }
    
    // Reintentar en errores 5xx
    if (error?.statusCode && error.statusCode >= 500) {
      return true
    }
    
    return false
  }
}
