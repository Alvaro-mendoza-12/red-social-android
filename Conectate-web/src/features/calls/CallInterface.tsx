import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import WebCallManager, { type CallState, type CallParticipant } from './WebCallManager'

export function CallInterface() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [callState, setCallState] = useState<CallState | null>(null)
  const [callManager, setCallManager] = useState<WebCallManager | null>(null)
  const [remoteUserId, setRemoteUserId] = useState<string>('')
  const [participantInfo, setParticipantInfo] = useState<CallParticipant | null>(null)
  const [isInitializing, setIsInitializing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const localVideoRef = useRef<HTMLVideoElement>(null)
  const remoteVideoRef = useRef<HTMLVideoElement>(null)
  const signalingChannelRef = useRef<BroadcastChannel | null>(null)

  // Initialize call manager
  useEffect(() => {
    if (!user) return

    try {
      const channel = new BroadcastChannel('conectate-calls')
      signalingChannelRef.current = channel
      
      const manager = new WebCallManager(user.id, channel)
      queueMicrotask(() => setCallManager(manager))

      // Setup callbacks
      manager.onCallState((state) => {
        setCallState(state)
        setError(null)
      })

      manager.onIncomingCall((from, participant) => {
        setRemoteUserId(from)
        setParticipantInfo(participant)
        setCallState({
          isIncomingCall: true,
          isOutgoingCall: false,
          currentCallId: `${from}-${user.id}-${Date.now()}`,
          remoteParticipant: participant,
          duration: 0,
          isMuted: false,
          isVideoEnabled: participant.isVideoEnabled,
          isScreenSharing: false
        })
      })

      manager.onCallEnd((reason) => {
        console.log('Call ended:', reason)
        setCallState(null)
        setRemoteUserId('')
        setParticipantInfo(null)
        
        // Navigate back after a short delay
        setTimeout(() => {
          navigate('/app/messages')
        }, 2000)
      })

      return () => {
        manager.cleanup()
        channel.close()
      }
    } catch (error) {
      console.error('Error initializing call manager:', error)
      queueMicrotask(() => setError('No se pudo inicializar el sistema de llamadas'))
    }
  }, [user, navigate])

  // Handle local video stream
  useEffect(() => {
    if (!callManager) return

    const setupLocalVideo = async () => {
      try {
        const stream = callManager.getLocalStream()
        if (stream && localVideoRef.current) {
          localVideoRef.current.srcObject = stream
        }
      } catch (error) {
        console.error('Error setting up local video:', error)
      }
    }

    setupLocalVideo()
  }, [callManager])

  const initiateCall = useCallback(async () => {
    if (!callManager || !remoteUserId.trim()) {
      setError('Ingresa un ID de usuario válido')
      return
    }

    setIsInitializing(true)
    setError(null)

    try {
      // In a real implementation, you would fetch user info from database
      const mockParticipantInfo: CallParticipant = {
        id: remoteUserId,
        name: `Usuario ${remoteUserId}`,
        avatarUrl: '',
        isAudioEnabled: true,
        isVideoEnabled: true,
        isSpeaking: false
      }

      await callManager.initiateCall(remoteUserId, {
        name: mockParticipantInfo.name,
        avatarUrl: mockParticipantInfo.avatarUrl,
        videoEnabled: true
      })

      setParticipantInfo(mockParticipantInfo)
      setIsInitializing(false)
    } catch (error) {
      console.error('Error initiating call:', error)
      setError('No se pudo iniciar la llamada')
      setIsInitializing(false)
    }
  }, [callManager, remoteUserId])

  const answerCall = useCallback(async () => {
    if (!callManager || !callState?.isIncomingCall) return

    setIsInitializing(true)

    try {
      await callManager.answerCall(callState.isVideoEnabled)
      setIsInitializing(false)
    } catch (error) {
      console.error('Error answering call:', error)
      setError('No se pudo contestar la llamada')
      setIsInitializing(false)
    }
  }, [callManager, callState])

  const rejectCall = useCallback(() => {
    if (!callManager) return

    try {
      callManager.rejectCall()
      setCallState(null)
      setRemoteUserId('')
      setParticipantInfo(null)
    } catch (error) {
      console.error('Error rejecting call:', error)
    }
  }, [callManager])

  const endCall = useCallback(() => {
    if (!callManager) return

    try {
      callManager.endCall('user_hang_up')
    } catch (error) {
      console.error('Error ending call:', error)
    }
  }, [callManager])

  const toggleMute = useCallback(() => {
    if (!callManager) return
    callManager.toggleMute()
  }, [callManager])

  const toggleVideo = useCallback(async () => {
    if (!callManager) return
    
    try {
      await callManager.toggleVideo()
    } catch (error) {
      console.error('Error toggling video:', error)
    }
  }, [callManager])

  const toggleScreenShare = useCallback(async () => {
    if (!callManager) return

    try {
      await callManager.toggleScreenShare()
    } catch (error) {
      console.error('Error toggling screen share:', error)
    }
  }, [callManager])

  if (!user) {
    return <StatusView title="Autenticación requerida" description="Debes iniciar sesión para hacer llamadas." />
  }

  if (error && !callState) {
    return (
      <div className="call-interface-container">
        <StatusView title="Error de llamadas" description={error} />
        <div className="call-actions">
          <button className="back-btn" onClick={() => navigate('/app/messages')}>
            Volver a mensajes
          </button>
        </div>
      </div>
    )
  }

  if (!callState) {
    // Show dialer interface
    return (
      <div className="call-interface-container">
        <header className="call-header">
          <h1>Nueva Llamada</h1>
          <p>Ingresa el ID del usuario para llamar</p>
        </header>

        <main className="call-main">
          <div className="dialer-section">
            <div className="user-input">
              <label htmlFor="userId">ID de Usuario:</label>
              <input
                id="userId"
                type="text"
                value={remoteUserId}
                onChange={(e) => setRemoteUserId(e.target.value)}
                placeholder="Ingresa el ID del usuario"
                className="user-id-input"
              />
            </div>

            <div className="dialer-actions">
              <button
                className="call-btn primary"
                onClick={initiateCall}
                disabled={!remoteUserId.trim() || isInitializing}
              >
                {isInitializing ? 'Llamando...' : '📞 Llamar'}
              </button>
            </div>
          </div>

          <div className="local-video-section">
            <h3>Vista Previa</h3>
            <video
              ref={localVideoRef}
              autoPlay
              muted
              playsInline
              className="local-video-preview"
            />
          </div>
        </main>

        <div className="call-footer">
          <button className="back-btn" onClick={() => navigate('/app/messages')}>
            Volver
          </button>
        </div>

        <style>{`
          .call-interface-container {
            height: 100vh;
            display: flex;
            flex-direction: column;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
          }

          .call-header {
            padding: 1rem;
            text-align: center;
            background: rgba(0, 0, 0, 0.1);
          }

          .call-header h1 {
            margin: 0;
            font-size: 2rem;
            font-weight: 600;
          }

          .call-header p {
            margin: 0.5rem 0 0;
            opacity: 0.8;
          }

          .call-main {
            flex: 1;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 2rem;
            gap: 2rem;
          }

          .dialer-section {
            background: rgba(255, 255, 255, 0.1);
            padding: 2rem;
            border-radius: 1rem;
            backdrop-filter: blur(10px);
            max-width: 400px;
            width: 100%;
          }

          .user-input {
            margin-bottom: 1.5rem;
          }

          .user-input label {
            display: block;
            margin-bottom: 0.5rem;
            font-weight: 500;
          }

          .user-id-input {
            width: 100%;
            padding: 0.75rem;
            border: 1px solid rgba(255, 255, 255, 0.3);
            background: rgba(255, 255, 255, 0.1);
            color: white;
            border-radius: 0.5rem;
            font-size: 1rem;
          }

          .user-id-input::placeholder {
            color: rgba(255, 255, 255, 0.6);
          }

          .dialer-actions {
            text-align: center;
          }

          .call-btn {
            background: #4CAF50;
            color: white;
            border: none;
            padding: 1rem 2rem;
            border-radius: 0.5rem;
            font-size: 1.1rem;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s;
            min-width: 150px;
          }

          .call-btn:hover:not(:disabled) {
            background: #45a049;
            transform: translateY(-2px);
          }

          .call-btn:disabled {
            background: #666;
            cursor: not-allowed;
            transform: none;
          }

          .local-video-section {
            text-align: center;
          }

          .local-video-section h3 {
            margin: 0 0 1rem 0;
            font-weight: 500;
          }

          .local-video-preview {
            width: 200px;
            height: 150px;
            border-radius: 0.5rem;
            background: #000;
            object-fit: cover;
            border: 2px solid rgba(255, 255, 255, 0.3);
          }

          .call-footer {
            padding: 1rem;
            text-align: center;
            background: rgba(0, 0, 0, 0.1);
          }

          .back-btn {
            background: rgba(255, 255, 255, 0.2);
            color: white;
            border: 1px solid rgba(255, 255, 255, 0.3);
            padding: 0.75rem 1.5rem;
            border-radius: 0.5rem;
            cursor: pointer;
            transition: background-color 0.2s;
          }

          .back-btn:hover {
            background: rgba(255, 255, 255, 0.3);
          }

          .call-actions {
            text-align: center;
            padding: 1rem;
          }
        `}</style>
      </div>
    )
  }

  // Show active call interface
  return (
    <div className="call-interface-container">
      <header className="call-header">
        <div className="call-info">
          <h2>
            {callState.isIncomingCall ? 'Llamada Entrante' : 'Llamada en Curso'}
          </h2>
          <p>
            {participantInfo?.name || 'Usuario Desconocido'} • 
            {Math.floor(callState.duration / 60)}:{(callState.duration % 60).toString().padStart(2, '0')}
          </p>
        </div>
        <div className="call-status">
          {callState.isIncomingCall && (
            <span className="incoming-indicator">📞 Entrante</span>
          )}
          {callState.isScreenSharing && (
            <span className="screen-share-indicator">🖥️ Compartiendo pantalla</span>
          )}
        </div>
      </header>

      <main className="call-main">
        <div className="videos-container">
          {/* Remote video */}
          <div className="video-wrapper remote-video">
            <video
              ref={remoteVideoRef}
              autoPlay
              playsInline
              className="remote-video-element"
            />
            <div className="participant-overlay">
              <div className="participant-info">
                <img 
                  src={participantInfo?.avatarUrl} 
                  alt={participantInfo?.name}
                  className="participant-avatar"
                />
                <span className="participant-name">{participantInfo?.name || 'Usuario'}</span>
              </div>
              <div className="participant-status">
                {callState.isMuted && <span className="muted-indicator">🔇</span>}
                {!callState.isVideoEnabled && <span className="video-off-indicator">📵</span>}
              </div>
            </div>
          </div>

          {/* Local video */}
          <div className="video-wrapper local-video">
            <video
              ref={localVideoRef}
              autoPlay
              muted
              playsInline
              className={`local-video-element ${callState.isMuted ? 'muted' : ''}`}
            />
            <div className="local-video-overlay">
              <span className="local-video-label">Tú</span>
              {callState.isMuted && <span className="muted-indicator">🔇</span>}
            </div>
          </div>
        </div>

        <div className="call-controls">
          {callState.isIncomingCall ? (
            <div className="incoming-controls">
              <button
                className="control-btn reject-btn"
                onClick={rejectCall}
                disabled={isInitializing}
              >
                🗑️ Rechazar
              </button>
              <button
                className="control-btn answer-btn"
                onClick={answerCall}
                disabled={isInitializing}
              >
                {isInitializing ? 'Conectando...' : '📞 Contestar'}
              </button>
            </div>
          ) : (
            <div className="active-controls">
              <button
                className={`control-btn ${callState.isMuted ? 'muted' : ''}`}
                onClick={toggleMute}
                title={callState.isMuted ? 'Activar micrófono' : 'Desactivar micrófono'}
              >
                {callState.isMuted ? '🔇' : '🎤'}
              </button>
              
              <button
                className={`control-btn ${callState.isVideoEnabled ? 'video-on' : 'video-off'}`}
                onClick={toggleVideo}
                title={callState.isVideoEnabled ? 'Desactivar vídeo' : 'Activar vídeo'}
              >
                {callState.isVideoEnabled ? '📹' : '📵'}
              </button>
              
              <button
                className={`control-btn ${callState.isScreenSharing ? 'screen-sharing' : ''}`}
                onClick={toggleScreenShare}
                title={callState.isScreenSharing ? 'Dejar de compartir pantalla' : 'Compartir pantalla'}
              >
                {callState.isScreenSharing ? '🖥️' : '🖥️'}
              </button>
              
              <button
                className="control-btn end-call-btn"
                onClick={endCall}
              >
                🗑️ Colgar
              </button>
            </div>
          )}
        </div>
      </main>

      <style>{`
        .call-interface-container {
          height: 100vh;
          display: flex;
          flex-direction: column;
          background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
          color: white;
        }

        .call-header {
          padding: 1rem;
          background: rgba(0, 0, 0, 0.1);
          display: flex;
          justify-content: space-between;
          align-items: center;
          backdrop-filter: blur(10px);
        }

        .call-info h2 {
          margin: 0;
          font-size: 1.5rem;
          font-weight: 600;
        }

        .call-info p {
          margin: 0.25rem 0 0;
          opacity: 0.8;
        }

        .call-status {
          display: flex;
          gap: 1rem;
          align-items: center;
        }

        .incoming-indicator {
          background: #f44336;
          padding: 0.25rem 0.5rem;
          border-radius: 0.25rem;
          font-size: 0.875rem;
        }

        .screen-share-indicator {
          background: #2196F3;
          padding: 0.25rem 0.5rem;
          border-radius: 0.25rem;
          font-size: 0.875rem;
        }

        .call-main {
          flex: 1;
          display: flex;
          flex-direction: column;
          padding: 1rem;
          gap: 1rem;
        }

        .videos-container {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 1rem;
          flex: 1;
        }

        .video-wrapper {
          position: relative;
          background: #000;
          border-radius: 0.75rem;
          overflow: hidden;
          aspect-ratio: 16/9;
        }

        .remote-video {
          border: 2px solid #4CAF50;
        }

        .local-video {
          border: 2px solid #2196F3;
        }

        .remote-video-element,
        .local-video-element {
          width: 100%;
          height: 100%;
          object-fit: cover;
        }

        .participant-overlay {
          position: absolute;
          bottom: 1rem;
          left: 1rem;
          right: 1rem;
          background: rgba(0, 0, 0, 0.7);
          padding: 0.5rem;
          border-radius: 0.5rem;
          display: flex;
          justify-content: space-between;
          align-items: center;
          backdrop-filter: blur(10px);
        }

        .participant-info {
          display: flex;
          align-items: center;
          gap: 0.5rem;
        }

        .participant-avatar {
          width: 2rem;
          height: 2rem;
          border-radius: 50%;
          object-fit: cover;
        }

        .participant-name {
          font-weight: 500;
        }

        .participant-status {
          display: flex;
          gap: 0.25rem;
        }

        .local-video-overlay {
          position: absolute;
          top: 0.5rem;
          right: 0.5rem;
          background: rgba(0, 0, 0, 0.7);
          padding: 0.25rem 0.5rem;
          border-radius: 0.25rem;
          display: flex;
          align-items: center;
          gap: 0.25rem;
          backdrop-filter: blur(10px);
        }

        .local-video-label {
          font-size: 0.875rem;
          font-weight: 500;
        }

        .muted-indicator {
          font-size: 1rem;
        }

        .video-off-indicator {
          font-size: 1rem;
        }

        .local-video.muted::after {
          content: '';
          position: absolute;
          top: 50%;
          left: 50%;
          transform: translate(-50%, -50%);
          width: 3rem;
          height: 3rem;
          background: #f44336;
          border-radius: 50%;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 1.5rem;
        }

        .call-controls {
          background: rgba(0, 0, 0, 0.1);
          padding: 1rem;
          border-radius: 0.75rem;
          backdrop-filter: blur(10px);
        }

        .incoming-controls,
        .active-controls {
          display: flex;
          gap: 1rem;
          justify-content: center;
        }

        .control-btn {
          background: rgba(255, 255, 255, 0.2);
          border: 1px solid rgba(255, 255, 255, 0.3);
          color: white;
          width: 3rem;
          height: 3rem;
          border-radius: 50%;
          cursor: pointer;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 1.25rem;
          transition: all 0.2s;
        }

        .control-btn:hover {
          background: rgba(255, 255, 255, 0.3);
          transform: scale(1.05);
        }

        .control-btn.muted {
          background: #f44336;
        }

        .control-btn.video-off {
          background: #666;
        }

        .control-btn.screen-sharing {
          background: #2196F3;
        }

        .reject-btn {
          background: #f44336;
        }

        .answer-btn {
          background: #4CAF50;
        }

        .end-call-btn {
          background: #f44336;
        }

        .control-btn:disabled {
          background: #666;
          cursor: not-allowed;
          transform: none;
        }
      `}</style>
    </div>
  )
}
