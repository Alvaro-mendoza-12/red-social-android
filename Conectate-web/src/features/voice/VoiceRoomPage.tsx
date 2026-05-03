import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import WebRTCManager from './WebRTCManager'

type VoiceRoom = {
  id: string
  title: string
  topic: string
  creatorId: string
  creatorName: string
  creatorAvatarUrl: string
  participantCount: number
  maxParticipants: number
  updatedAt: string
}

type Participant = {
  id: string
  userId: string
  userName: string
  userAvatarUrl: string
  isMuted: boolean
  isSpeaking: boolean
  joinedAt: string
}

export function VoiceRoomPage() {
  const { user } = useAuth()
  const { roomId } = useParams<{ roomId: string }>()
  const navigate = useNavigate()
  const [room, setRoom] = useState<VoiceRoom | null>(null)
  const [participants, setParticipants] = useState<Participant[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [isJoined, setIsJoined] = useState(false)
  const [isMuted, setIsMuted] = useState(false)
  const [isSpeaking, setIsSpeaking] = useState(false)
  const [isAudioEnabled, setIsAudioEnabled] = useState(true)
  const [isVideoEnabled, setIsVideoEnabled] = useState(false)
  
  const localVideoRef = useRef<HTMLVideoElement>(null)
  const remoteVideoRefs = useRef<Map<string, HTMLVideoElement>>(new Map())
  const webrtcManagerRef = useRef<WebRTCManager | null>(null)
  const audioContextRef = useRef<AudioContext | null>(null)
  const analyserRef = useRef<AnalyserNode | null>(null)

  const loadRoom = useCallback(async () => {
    if (!supabase || !roomId) {
      setLoading(false)
      setError('ID de sala no válido')
      return
    }

    try {
      const { data, error } = await supabase
        .from('voice_rooms')
        .select('*')
        .eq('id', roomId)
        .limit(1)

      if (error) throw error
      if (!data || data.length === 0) {
        setError('Sala no encontrada')
        setLoading(false)
        return
      }

      const roomData = data[0] as GenericRow
      const mappedRoom: VoiceRoom = {
        id: textOf(roomData, ['id'], roomId),
        title: textOf(roomData, ['name', 'title'], 'Sala de voz'),
        topic: textOf(roomData, ['topic', 'description'], 'Sin descripción'),
        creatorId: textOf(roomData, ['creator_id', 'owner_id'], ''),
        creatorName: textOf(roomData, ['creator_name', 'owner_name'], ''),
        creatorAvatarUrl: textOf(roomData, ['creator_avatar_url', 'avatar_url'], ''),
        participantCount: Number(roomData.current_participants ?? roomData.participant_count ?? 0),
        maxParticipants: Number(roomData.max_participants ?? 10),
        updatedAt: textOf(roomData, ['updated_at', 'created_at'], '')
      }

      setRoom(mappedRoom)
      
      // Load participants
      await loadParticipants(roomId)
      
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar la sala'))
      setLoading(false)
    }
  }, [roomId])

  const loadParticipants = useCallback(async (roomId: string) => {
    if (!supabase) return

    try {
      const { data, error } = await supabase
        .from('voice_room_participants')
        .select('*')
        .eq('room_id', roomId)
        .order('joined_at', { ascending: true })

      if (error) throw error

      const mappedParticipants = ((data ?? []) as GenericRow[]).map((participant) => ({
        id: textOf(participant, ['id'], crypto.randomUUID()),
        userId: textOf(participant, ['user_id'], ''),
        userName: textOf(participant, ['user_name', 'display_name'], ''),
        userAvatarUrl: textOf(participant, ['user_avatar_url', 'avatar_url'], ''),
        isMuted: Boolean(participant.is_muted),
        isSpeaking: false,
        joinedAt: textOf(participant, ['joined_at'], '')
      }))

      setParticipants(mappedParticipants)
    } catch (caught) {
      console.error('Error loading participants:', caught)
    }
  }, [])

  const initializeWebRTC = useCallback(async () => {
    if (!user) return

    try {
      const manager = new WebRTCManager(user.id)
      webrtcManagerRef.current = manager

      // Initialize local media
      const stream = await manager.initializeLocalMedia(true, false) // Audio only by default
      if (localVideoRef.current) {
        localVideoRef.current.srcObject = stream
      }

      // Setup callbacks
      manager.onTrack((track, stream) => {
        const peerId = 'remote' // In a real implementation, this would be the actual peer ID
        let videoElement = remoteVideoRefs.current.get(peerId)
        
        if (!videoElement) {
          videoElement = document.createElement('video')
          videoElement.autoplay = true
          videoElement.playsInline = true
          videoElement.className = 'remote-video'
          remoteVideoRefs.current.set(peerId, videoElement)
        }
        
        videoElement.srcObject = stream
      })

      manager.onDataChannel((data, from) => {
        console.log('Received data from', from, data)
        // Handle voice room data (mute/unmute, speaking status, etc.)
      })

      // Initialize audio context for speaking detection
      audioContextRef.current = new (window.AudioContext || (window as any).webkitAudioContext)()
      analyserRef.current = audioContextRef.current.createAnalyser()
      analyserRef.current.fftSize = 256
      
      const source = audioContextRef.current.createMediaStreamSource(stream)
      source.connect(analyserRef.current)
      
      // Start speaking detection
      detectSpeaking()
      
    } catch (error) {
      console.error('Error initializing WebRTC:', error)
      setError('No se pudo inicializar el audio')
    }
  }, [user])

  const detectSpeaking = useCallback(() => {
    if (!analyserRef.current) return

    const dataArray = new Uint8Array(analyserRef.current.frequencyBinCount)
    analyserRef.current.getByteFrequencyData(dataArray)
    
    const average = dataArray.reduce((sum, value) => sum + value, 0) / dataArray.length
    const isSpeaking = average > 30 // Threshold for speaking detection
    
    setIsSpeaking(isSpeaking)
    
    requestAnimationFrame(detectSpeaking)
  }, [])

  const joinRoom = useCallback(async () => {
    if (!supabase || !user || !room) return

    try {
      // Join room in database
      const { error } = await supabase
        .from('voice_room_participants')
        .upsert({
          room_id: room.id,
          user_id: user.id,
          user_name: user.user_metadata?.display_name || user.email || '',
          user_avatar_url: user.user_metadata?.avatar_url || '',
          is_muted: isMuted,
          joined_at: new Date().toISOString()
        }, { onConflict: 'room_id,user_id' })

      if (error) throw error

      setIsJoined(true)
      
      // Initialize WebRTC
      await initializeWebRTC()
      
      // Update participant count
      const { error: updateError } = await supabase.rpc('increment_participant_count', { room_id: room.id })
      if (updateError) {
        console.warn('Error updating participant count:', updateError)
      }
      
      // Reload participants
      await loadParticipants(room.id)
      
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo unir a la sala'))
    }
  }, [user, room, isMuted, initializeWebRTC, loadParticipants])

  const leaveRoom = useCallback(async () => {
    if (!supabase || !user || !room) return

    try {
      // Leave room in database
      const { error } = await supabase
        .from('voice_room_participants')
        .delete()
        .eq('room_id', room.id)
        .eq('user_id', user.id)

      if (error) throw error

      setIsJoined(false)
      
      // Cleanup WebRTC
      if (webrtcManagerRef.current) {
        webrtcManagerRef.current.disconnectAll()
        webrtcManagerRef.current.stopLocalStream()
      }
      
      // Clean up audio context
      if (audioContextRef.current) {
        audioContextRef.current.close()
      }
      
      // Update participant count
      const { error: updateError } = await supabase.rpc('decrement_participant_count', { room_id: room.id })
      if (updateError) {
        console.warn('Error updating participant count:', updateError)
      }
      
      navigate('/app/voice')
      
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo salir de la sala'))
    }
  }, [user, room, navigate])

  const toggleMute = useCallback(async () => {
    if (!supabase || !user || !room) return

    const newMutedState = !isMuted
    setIsMuted(newMutedState)

    try {
      const { error } = await supabase
        .from('voice_room_participants')
        .update({ is_muted: newMutedState })
        .eq('room_id', room.id)
        .eq('user_id', user.id)

      if (error) throw error

      // Update local stream
      if (webrtcManagerRef.current) {
        const stream = webrtcManagerRef.current.getLocalStream()
        if (stream) {
          stream.getAudioTracks().forEach(track => {
            track.enabled = !newMutedState
          })
        }
      }
      
    } catch (caught) {
      console.error('Error toggling mute:', caught)
      setIsMuted(!newMutedState) // Revert on error
    }
  }, [isMuted, user, room])

  const toggleVideo = useCallback(async () => {
    if (!webrtcManagerRef.current) return

    const newVideoState = !isVideoEnabled
    setIsVideoEnabled(newVideoState)

    try {
      // Reinitialize media with new video settings
      const stream = await webrtcManagerRef.current.initializeLocalMedia(true, newVideoState)
      if (localVideoRef.current) {
        localVideoRef.current.srcObject = stream
      }
      
    } catch (caught) {
      console.error('Error toggling video:', caught)
      setIsVideoEnabled(!newVideoState) // Revert on error
    }
  }, [isVideoEnabled])

  useEffect(() => {
    loadRoom()
  }, [loadRoom])

  useEffect(() => {
    if (!supabase || !room) return

    const channel = supabase
      .channel(`voice-room-${room.id}`)
      .on('postgres_changes', { 
        event: '*', 
        schema: 'public', 
        table: 'voice_room_participants',
        filter: `room_id=eq.${room.id}`
      }, () => {
        loadParticipants(room.id)
      })
      .subscribe()

    return () => {
      supabase.removeChannel(channel)
    }
  }, [room, loadParticipants])

  if (loading) {
    return <StatusView title="Cargando sala" description="Preparando sala de voz..." />
  }

  if (error && !room) {
    return <StatusView title="Error" description={error} />
  }

  if (!room) {
    return <StatusView title="Sala no encontrada" description="La sala de voz no existe o fue eliminada." />
  }

  return (
    <div className="voice-room-container">
      <header className="voice-room-header">
        <div className="room-info">
          <h1>{room.title}</h1>
          <p>{room.topic}</p>
          <span className="participant-count">
            {participants.length + (isJoined ? 1 : 0)}/{room.maxParticipants} participantes
          </span>
        </div>
        <div className="room-actions">
          {!isJoined ? (
            <button 
              className="join-btn" 
              onClick={joinRoom}
              disabled={participants.length >= room.maxParticipants}
            >
              Unirse a la sala
            </button>
          ) : (
            <div className="in-room-controls">
              <button 
                className={`control-btn ${isMuted ? 'muted' : ''}`}
                onClick={toggleMute}
                title={isMuted ? 'Activar micrófono' : 'Desactivar micrófono'}
              >
                {isMuted ? '🔇' : '🎤'}
              </button>
              <button 
                className={`control-btn ${isVideoEnabled ? 'video-on' : 'video-off'}`}
                onClick={toggleVideo}
                title={isVideoEnabled ? 'Desactivar vídeo' : 'Activar vídeo'}
              >
                {isVideoEnabled ? '📹' : '📵'}
              </button>
              <button 
                className="control-btn leave-btn" 
                onClick={leaveRoom}
              >
                Salir de la sala
              </button>
            </div>
          )}
        </div>
      </header>

      <main className="voice-room-main">
        <div className="participants-grid">
          {/* Local video */}
          {isJoined && (
            <div className="participant local-participant">
              <video 
                ref={localVideoRef}
                autoPlay 
                muted 
                playsInline
                className={`participant-video ${isSpeaking ? 'speaking' : ''} ${isMuted ? 'muted' : ''}`}
              />
              <div className="participant-info">
                <span className="participant-name">Tú</span>
                <div className="participant-status">
                  {isMuted && <span className="muted-indicator">🔇</span>}
                  {isSpeaking && <span className="speaking-indicator">🗣️</span>}
                </div>
              </div>
            </div>
          )}

          {/* Remote participants */}
          {participants.map((participant) => (
            <div key={participant.id} className="participant">
              <div className="participant-video-placeholder">
                {participant.userAvatarUrl ? (
                  <img 
                    src={participant.userAvatarUrl} 
                    alt={participant.userName}
                    className="participant-avatar"
                  />
                ) : (
                  <div className="participant-avatar-fallback">
                    {participant.userName.charAt(0).toUpperCase()}
                  </div>
                )}
              </div>
              <div className="participant-info">
                <span className="participant-name">{participant.userName}</span>
                <div className="participant-status">
                  {participant.isMuted && <span className="muted-indicator">🔇</span>}
                  {participant.isSpeaking && <span className="speaking-indicator">🗣️</span>}
                </div>
              </div>
            </div>
          ))}
        </div>
      </main>

      <style jsx>{`
        .voice-room-container {
          height: 100vh;
          display: flex;
          flex-direction: column;
          background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        }

        .voice-room-header {
          padding: 1rem;
          background: rgba(0, 0, 0, 0.8);
          color: white;
          display: flex;
          justify-content: space-between;
          align-items: center;
          backdrop-filter: blur(10px);
        }

        .room-info h1 {
          margin: 0;
          font-size: 1.5rem;
          font-weight: 600;
        }

        .room-info p {
          margin: 0.25rem 0 0;
          opacity: 0.8;
        }

        .participant-count {
          font-size: 0.875rem;
          opacity: 0.7;
        }

        .join-btn {
          background: #4CAF50;
          color: white;
          border: none;
          padding: 0.75rem 1.5rem;
          border-radius: 0.5rem;
          font-weight: 600;
          cursor: pointer;
          transition: background-color 0.2s;
        }

        .join-btn:hover:not(:disabled) {
          background: #45a049;
        }

        .join-btn:disabled {
          background: #666;
          cursor: not-allowed;
        }

        .in-room-controls {
          display: flex;
          gap: 0.5rem;
        }

        .control-btn {
          background: rgba(255, 255, 255, 0.2);
          border: 1px solid rgba(255, 255, 255, 0.3);
          color: white;
          width: 2.5rem;
          height: 2.5rem;
          border-radius: 50%;
          cursor: pointer;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 1rem;
          transition: all 0.2s;
        }

        .control-btn:hover {
          background: rgba(255, 255, 255, 0.3);
        }

        .control-btn.muted {
          background: #f44336;
        }

        .control-btn.leave-btn {
          background: #f44336;
        }

        .voice-room-main {
          flex: 1;
          padding: 1rem;
          overflow-y: auto;
        }

        .participants-grid {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
          gap: 1rem;
          max-width: 1200px;
          margin: 0 auto;
        }

        .participant {
          background: rgba(0, 0, 0, 0.6);
          border-radius: 0.75rem;
          overflow: hidden;
          aspect-ratio: 16/9;
          position: relative;
        }

        .participant.local-participant {
          border: 2px solid #4CAF50;
        }

        .participant-video {
          width: 100%;
          height: 100%;
          object-fit: cover;
        }

        .participant-video-placeholder {
          width: 100%;
          height: 100%;
          display: flex;
          align-items: center;
          justify-content: center;
          background: rgba(0, 0, 0, 0.4);
        }

        .participant-avatar {
          width: 4rem;
          height: 4rem;
          border-radius: 50%;
          object-fit: cover;
        }

        .participant-avatar-fallback {
          width: 4rem;
          height: 4rem;
          border-radius: 50%;
          background: #666;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 1.5rem;
          font-weight: 600;
          color: white;
        }

        .participant-info {
          position: absolute;
          bottom: 0.5rem;
          left: 0.5rem;
          right: 0.5rem;
          background: rgba(0, 0, 0, 0.8);
          padding: 0.25rem 0.5rem;
          border-radius: 0.25rem;
          display: flex;
          justify-content: space-between;
          align-items: center;
        }

        .participant-name {
          color: white;
          font-size: 0.875rem;
          font-weight: 500;
        }

        .participant-status {
          display: flex;
          gap: 0.25rem;
        }

        .muted-indicator, .speaking-indicator {
          font-size: 0.75rem;
        }

        .participant-video.speaking {
          box-shadow: 0 0 0 3px #4CAF50;
        }

        .participant-video.muted::after {
          content: '';
          position: absolute;
          top: 0.5rem;
          right: 0.5rem;
          background: #f44336;
          color: white;
          padding: 0.25rem;
          border-radius: 50%;
          font-size: 0.75rem;
        }
      `}</style>
    </div>
  )
}
