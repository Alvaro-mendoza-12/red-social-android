import { useState, useEffect, useRef, useCallback } from 'react'
import { supabase } from '../api/EnterpriseApiClient'

/**
 * 🔥 WEBRTC HOOK - NIVEL DISCORD/ZOOM
 * Implementación real de WebRTC para voice rooms
 */

interface WebRTCConfig {
  iceServers: RTCIceServer[]
}

interface PeerConnection {
  id: string
  connection: RTCPeerConnection
  stream?: MediaStream
}

interface VoiceRoomState {
  isConnected: boolean
  isMuted: boolean
  isDeafened: boolean
  participants: Participant[]
  currentTrack?: TrackInfo
  volume: number
}

interface Participant {
  id: string
  username: string
  avatar?: string
  isSpeaking: boolean
  isMuted: boolean
  stream?: MediaStream
}

interface TrackInfo {
  title: string
  artist: string
  duration: number
  currentTime: number
  cover?: string
}

export const useWebRTC = (roomId: string) => {
  const [state, setState] = useState<VoiceRoomState>({
    isConnected: false,
    isMuted: false,
    isDeafened: false,
    participants: [],
    volume: 0.7
  })
  
  const localStreamRef = useRef<MediaStream | null>(null)
  const peerConnectionsRef = useRef<Map<string, PeerConnection>>(new Map())
  const audioContextRef = useRef<AudioContext | null>(null)
  const gainNodeRef = useRef<GainNode | null>(null)
  
  // 🔥 CONFIGURACIÓN WEBRTC
  const rtcConfig: WebRTCConfig = {
    iceServers: [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' },
      { urls: 'stun:stun2.l.google.com:19302' },
      { urls: 'stun:stun3.l.google.com:19302' },
      { urls: 'stun:stun4.l.google.com:19302' }
    ]
  }
  
  // 🔥 INICIAR AUDIO LOCAL
  const startLocalAudio = useCallback(async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          sampleRate: 48000,
          channelCount: 1
        },
        video: false
      })
      
      localStreamRef.current = stream
      
      // Configurar audio context para volumen
      audioContextRef.current = new (window.AudioContext || (window as any).webkitAudioContext)()
      gainNodeRef.current = audioContextRef.current.createGain()
      gainNodeRef.current.gain.value = state.volume
      
      const source = audioContextRef.current.createMediaStreamSource(stream)
      source.connect(gainNodeRef.current)
      gainNodeRef.current.connect(audioContextRef.current.destination)
      
      setState(prev => ({ ...prev, isConnected: true }))
      
    } catch (error) {
      console.error('Error accessing microphone:', error)
      throw new Error('No se pudo acceder al micrófono')
    }
  }, [state.volume])
  
  // 🔥 DETENER AUDIO LOCAL
  const stopLocalAudio = useCallback(() => {
    if (localStreamRef.current) {
      localStreamRef.current.getTracks().forEach(track => track.stop())
      localStreamRef.current = null
    }
    
    if (audioContextRef.current) {
      audioContextRef.current.close()
      audioContextRef.current = null
      gainNodeRef.current = null
    }
    
    setState(prev => ({ ...prev, isConnected: false }))
  }, [])
  
  // 🔥 CREAR PEER CONNECTION
  const createPeerConnection = useCallback((participantId: string): RTCPeerConnection => {
    const pc = new RTCPeerConnection(rtcConfig)
    
    // Añadir stream local
    if (localStreamRef.current) {
      localStreamRef.current.getTracks().forEach(track => {
        pc.addTrack(track, localStreamRef.current!)
      })
    }
    
    // Manejar ICE candidates
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        // Enviar ICE candidate al servidor de signaling
        supabase
          .channel(`voice-room-${roomId}`)
          .send({
            type: 'ice-candidate',
            candidate: event.candidate,
            from: 'current-user',
            to: participantId
          })
      }
    }
    
    // Manejar tracks remotos
    pc.ontrack = (event) => {
      if (event.streams[0]) {
        setState(prev => ({
          ...prev,
          participants: prev.participants.map(p =>
            p.id === participantId
              ? { ...p, stream: event.streams[0] }
              : p
          )
        }))
      }
    }
    
    // Detectar cuando alguien está hablando
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'connected') {
        // Analizar audio para detectar si está hablando
        analyzeAudioStream(participantId, event.streams[0])
      }
    }
    
    return pc
  }, [roomId])
  
  // 🔥 ANALIZAR AUDIO PARA DETECTAR HABLA
  const analyzeAudioStream = useCallback((participantId: string, stream?: MediaStream) => {
    if (!stream || !audioContextRef.current) return
    
    const analyser = audioContextRef.current.createAnalyser()
    const source = audioContextRef.current.createMediaStreamSource(stream)
    source.connect(analyser)
    
    analyser.fftSize = 256
    const bufferLength = analyser.frequencyBinCount
    const dataArray = new Uint8Array(bufferLength)
    
    const checkSpeaking = () => {
      analyser.getByteFrequencyData(dataArray)
      const average = dataArray.reduce((sum, value) => sum + value, 0) / bufferLength
      
      const isSpeaking = average > 30 // Umbral para detectar habla
      
      setState(prev => ({
        ...prev,
        participants: prev.participants.map(p =>
          p.id === participantId
            ? { ...p, isSpeaking }
            : p
        )
      }))
      
      requestAnimationFrame(checkSpeaking)
    }
    
    checkSpeaking()
  }, [])
  
  // 🔥 UNIRSE A SALA DE VOZ
  const joinVoiceRoom = useCallback(async () => {
    try {
      // Iniciar audio local
      await startLocalAudio()
      
      // Suscribirse a eventos de la sala
      const channel = supabase.channel(`voice-room-${roomId}`)
      
      channel.on('broadcast', { event: 'user-joined' }, (payload) => {
        const { participant } = payload.payload
        
        // Crear peer connection para el nuevo participante
        const pc = createPeerConnection(participant.id)
        peerConnectionsRef.current.set(participant.id, { id: participant.id, connection: pc })
        
        setState(prev => ({
          ...prev,
          participants: [...prev.participants, participant]
        }))
        
        // Iniciar oferta
        createOffer(participant.id, pc)
      })
      
      channel.on('broadcast', { event: 'user-left' }, (payload) => {
        const { participantId } = payload.payload
        
        // Cerrar peer connection
        const pc = peerConnectionsRef.current.get(participantId)
        if (pc) {
          pc.connection.close()
          peerConnectionsRef.current.delete(participantId)
        }
        
        setState(prev => ({
          ...prev,
          participants: prev.participants.filter(p => p.id !== participantId)
        }))
      })
      
      channel.on('broadcast', { event: 'offer' }, async (payload) => {
        const { from, offer } = payload.payload
        
        const pc = createPeerConnection(from)
        peerConnectionsRef.current.set(from, { id: from, connection: pc })
        
        await pc.setRemoteDescription(new RTCSessionDescription(offer))
        const answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        
        // Enviar respuesta
        channel.send({
          type: 'answer',
          answer,
          from: 'current-user',
          to: from
        })
      })
      
      channel.on('broadcast', { event: 'answer' }, async (payload) => {
        const { from, answer } = payload.payload
        
        const pc = peerConnectionsRef.current.get(from)
        if (pc) {
          await pc.setRemoteDescription(new RTCSessionDescription(answer))
        }
      })
      
      channel.on('broadcast', { event: 'ice-candidate' }, async (payload) => {
        const { from, candidate } = payload.payload
        
        const pc = peerConnectionsRef.current.get(from)
        if (pc) {
          await pc.addIceCandidate(new RTCIceCandidate(candidate))
        }
      })
      
      channel.subscribe()
      
    } catch (error) {
      console.error('Error joining voice room:', error)
      throw error
    }
  }, [roomId, startLocalAudio, createPeerConnection])
  
  // 🔥 CREAR OFERTA
  const createOffer = useCallback(async (participantId: string, pc: RTCPeerConnection) => {
    try {
      const offer = await pc.createOffer()
      await pc.setLocalDescription(offer)
      
      // Enviar oferta
      supabase
        .channel(`voice-room-${roomId}`)
        .send({
          type: 'offer',
          offer,
          from: 'current-user',
          to: participantId
        })
    } catch (error) {
      console.error('Error creating offer:', error)
    }
  }, [roomId])
  
  // 🔥 SALIR DE SALA DE VOZ
  const leaveVoiceRoom = useCallback(() => {
    // Detener audio local
    stopLocalAudio()
    
    // Cerrar todas las peer connections
    peerConnectionsRef.current.forEach((pc) => {
      pc.connection.close()
    })
    peerConnectionsRef.current.clear()
    
    // Limpiar estado
    setState({
      isConnected: false,
      isMuted: false,
      isDeafened: false,
      participants: [],
      volume: 0.7
    })
    
    // Desuscribirse del canal
    supabase.channel(`voice-room-${roomId}`).unsubscribe()
  }, [roomId, stopLocalAudio])
  
  // 🔥 MUTEAR/DESMUTEAR
  const toggleMute = useCallback(() => {
    if (localStreamRef.current) {
      const audioTrack = localStreamRef.current.getAudioTracks()[0]
      if (audioTrack) {
        audioTrack.enabled = !audioTrack.enabled
        setState(prev => ({ ...prev, isMuted: !prev.isMuted }))
      }
    }
  }, [])
  
  // 🔥 SILENCIAR/DESCILENCIAR
  const toggleDeafen = useCallback(() => {
    if (gainNodeRef.current) {
      const newVolume = state.isDeafened ? state.volume : 0
      gainNodeRef.current.gain.value = newVolume
      setState(prev => ({ ...prev, isDeafened: !prev.isDeafened }))
    }
  }, [state.volume, state.isDeafened])
  
  // 🔥 AJUSTAR VOLUMEN
  const setVolume = useCallback((volume: number) => {
    const clampedVolume = Math.max(0, Math.min(1, volume))
    
    if (gainNodeRef.current && !state.isDeafened) {
      gainNodeRef.current.gain.value = clampedVolume
    }
    
    setState(prev => ({ ...prev, volume: clampedVolume }))
  }, [state.isDeafened])
  
  // 🔥 LIMPIEZA
  useEffect(() => {
    return () => {
      leaveVoiceRoom()
    }
  }, [leaveVoiceRoom])
  
  return {
    ...state,
    joinVoiceRoom,
    leaveVoiceRoom,
    toggleMute,
    toggleDeafen,
    setVolume
  }
}

/**
 * 🔥 DISCO PLAYER HOOK - NIVEL SPOTIFY
 * Reproductor de música para disco rooms
 */
export const useDiscoPlayer = (roomId: string) => {
  const [isPlaying, setIsPlaying] = useState(false)
  const [currentTrack, setCurrentTrack] = useState<TrackInfo | null>(null)
  const [volume, setVolume] = useState(0.7)
  const [isMuted, setIsMuted] = useState(false)
  const [queue, setQueue] = useState<TrackInfo[]>([])
  const [position, setPosition] = useState(0)
  const [duration, setDuration] = useState(0)
  
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const positionIntervalRef = useRef<NodeJS.Timeout | null>(null)
  
  // 🔥 REPRODUCIR TRACK
  const playTrack = useCallback((track: TrackInfo) => {
    if (audioRef.current) {
      audioRef.current.src = track.url || ''
      audioRef.current.play()
      setIsPlaying(true)
      setCurrentTrack(track)
      setPosition(0)
      setDuration(track.duration)
    }
  }, [])
  
  // 🔥 PAUSAR/REANUDAR
  const togglePlayPause = useCallback(() => {
    if (audioRef.current) {
      if (isPlaying) {
        audioRef.current.pause()
        setIsPlaying(false)
      } else {
        audioRef.current.play()
        setIsPlaying(true)
      }
    }
  }, [isPlaying])
  
  // 🔥 SIGUIENTE TRACK
  const playNext = useCallback(() => {
    const currentIndex = queue.findIndex(t => t.title === currentTrack?.title)
    const nextIndex = (currentIndex + 1) % queue.length
    
    if (queue[nextIndex]) {
      playTrack(queue[nextIndex])
    }
  }, [queue, currentTrack, playTrack])
  
  // 🔥 TRACK ANTERIOR
  const playPrevious = useCallback(() => {
    const currentIndex = queue.findIndex(t => t.title === currentTrack?.title)
    const prevIndex = currentIndex === 0 ? queue.length - 1 : currentIndex - 1
    
    if (queue[prevIndex]) {
      playTrack(queue[prevIndex])
    }
  }, [queue, currentTrack, playTrack])
  
  // 🔥 AJUSTAR VOLUMEN
  const adjustVolume = useCallback((newVolume: number) => {
    const clampedVolume = Math.max(0, Math.min(1, newVolume))
    setVolume(clampedVolume)
    
    if (audioRef.current) {
      audioRef.current.volume = clampedVolume
    }
  }, [])
  
  // 🔥 MUTAR/DESMUTAR
  const toggleMute = useCallback(() => {
    if (audioRef.current) {
      audioRef.current.muted = !audioRef.current.muted
      setIsMuted(!isMuted)
    }
  }, [isMuted])
  
  // 🔥 BUSCAR POSICIÓN
  const seekTo = useCallback((time: number) => {
    if (audioRef.current) {
      audioRef.current.currentTime = time
      setPosition(time)
    }
  }, [])
  
  // 🔥 MONITOREAR POSICIÓN
  useEffect(() => {
    if (isPlaying) {
      positionIntervalRef.current = setInterval(() => {
        if (audioRef.current) {
          setPosition(audioRef.current.currentTime)
        }
      }, 100)
    } else {
      if (positionIntervalRef.current) {
        clearInterval(positionIntervalRef.current)
      }
    }
    
    return () => {
      if (positionIntervalRef.current) {
        clearInterval(positionIntervalRef.current)
      }
    }
  }, [isPlaying])
  
  // 🔥 CARGAR QUEUE
  useEffect(() => {
    const loadQueue = async () => {
      try {
        const { data } = await supabase
          .from('disco_room_queue')
          .select(`
            *,
            track:tracks(*)
          `)
          .eq('disco_room_id', roomId)
          .order('position', { ascending: true })
        
        if (data) {
          setQueue(data.map(item => item.track))
        }
      } catch (error) {
        console.error('Error loading queue:', error)
      }
    }
    
    loadQueue()
  }, [roomId])
  
  return {
    isPlaying,
    currentTrack,
    queue,
    position,
    duration,
    volume,
    isMuted,
    playTrack,
    togglePlayPause,
    playNext,
    playPrevious,
    adjustVolume,
    toggleMute,
    seekTo
  }
}
