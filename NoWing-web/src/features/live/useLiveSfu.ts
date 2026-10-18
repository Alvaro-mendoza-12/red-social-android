import { useEffect, useRef, useState } from 'react'
import { Room, RoomEvent, Track, type LocalTrackPublication } from 'livekit-client'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { useAuth } from '../../core/auth/AuthProvider'
import { syncMediaTracks } from '../../core/media/stableMediaStream'

type LiveRole = 'host' | 'guest' | 'viewer'
export type SfuParticipant = { id: string; name: string; stream: MediaStream; videoEnabled: boolean }
export function useLiveSfu({ liveId, hostId, role, enabled, localMedia, source, micEnabled, videoEnabled }: {
  liveId: string; hostId: string; role: LiveRole; enabled: boolean; localMedia: MediaStream | null
  source: 'camera' | 'screen'; micEnabled: boolean; videoEnabled: boolean
}) {
  const { user } = useAuth(), [room,setRoom] = useState<Room|null>(null), [participants,setParticipants] = useState<SfuParticipant[]>([]), [connected,setConnected] = useState(false), [error,setError] = useState(''), [generation,setGeneration] = useState(0)
  const publicationsRef = useRef<LocalTrackPublication[]>([])
  const published = useRef<MediaStreamTrack[]>([]), requested = useRef({micEnabled,videoEnabled})
  useEffect(() => { requested.current = {micEnabled,videoEnabled} }, [micEnabled,videoEnabled])
  useEffect(()=>{
    if(!enabled||!user?.id||!liveId)return
    const controller=new AbortController(), session=new Room({adaptiveStream:false,dynacast:true,disconnectOnPageLeave:true,stopLocalTrackOnUnpublish:true})
    let active=true
    const streams = new Map<string, MediaStream>()
    const update=()=>{if(!active)return
      for (const id of streams.keys()) if (!session.remoteParticipants.has(id)) streams.delete(id)
      setParticipants([...session.remoteParticipants.values()].map(participant=>{
      const tracks=[...participant.trackPublications.values()].filter(publication=>publication.isSubscribed&&!publication.isMuted&&publication.track).map(publication=>publication.track!.mediaStreamTrack)
      const stream = syncMediaTracks(streams.get(participant.identity) || new MediaStream(), tracks)
      streams.set(participant.identity, stream)
      return {id:participant.identity,name:participant.name||'Participante',stream,videoEnabled:tracks.some(track=>track.kind==='video'&&track.readyState==='live')}
    }))}
    session.on(RoomEvent.TrackSubscribed,update).on(RoomEvent.TrackUnsubscribed,update).on(RoomEvent.TrackMuted,update).on(RoomEvent.TrackUnmuted,update).on(RoomEvent.ParticipantConnected,update).on(RoomEvent.ParticipantDisconnected,update)
    session.on(RoomEvent.Reconnecting,()=>{if(active)setConnected(false)})
    session.on(RoomEvent.Reconnected,()=>{if(active){setConnected(true);setError('');update()}})
    session.on(RoomEvent.Disconnected,()=>{if(active){setConnected(false);setRoom(null);setError('Se interrumpió la conexión del directo. Puedes volver a conectar.')}})
    void(async()=>{
      try{
        // Start the external connection lifecycle asynchronously; cleanup can cancel it.
        await Promise.resolve()
        if(!active)return
        setRoom(null);setParticipants([]);setConnected(false);setError('')
        const ticket=await fallbackApiJson<{wsUrl:string;token:string}>(`/v1/data/live/${encodeURIComponent(liveId)}/sfu-token`,{method:'POST',expectedAccountId:user.id,signal:controller.signal,body:JSON.stringify({role})})
        if(!active)return
        await session.connect(ticket.wsUrl,ticket.token,{autoSubscribe:true,rtcConfig:{iceTransportPolicy:'all'}})
        if(!active){await session.disconnect();return}
        setConnected(true);setRoom(session);update()
        await fallbackApiJson(`/v1/data/live/${encodeURIComponent(liveId)}/participants/state`,{method:'POST',expectedAccountId:user.id,signal:controller.signal,body:JSON.stringify({state:'connected'})})
      }catch(error){
        if(active){
          console.warn('[live-sfu] No se pudo abrir el transporte multimedia', error instanceof Error ? error.name : 'error desconocido')
          setError('No se pudo conectar el audio y video del directo. Intenta conectar de nuevo.')
        }
      }
    })()
    return()=>{active=false;controller.abort();session.removeAllListeners();void session.disconnect();streams.clear();published.current.forEach(track=>track.stop());published.current=[]}
  },[enabled,liveId,user?.id,role,generation])
  useEffect(()=>{
    if(!room||!localMedia||role==='viewer')return
    let active=true;const tracks=localMedia.getTracks().filter(track=>track.readyState==='live').map(track=>track.clone()),publications:LocalTrackPublication[]=[]
    published.current=tracks; publicationsRef.current=publications
    void(async()=>{
      try{
        for(const track of tracks){
          track.enabled=track.kind==='audio'?requested.current.micEnabled:requested.current.videoEnabled
          const publication=await room.localParticipant.publishTrack(track,{source:track.kind==='audio'?Track.Source.Microphone:source==='screen'?Track.Source.ScreenShare:Track.Source.Camera,simulcast:track.kind==='video'})
          if(!active){await room.localParticipant.unpublishTrack(publication.track!,true);continue}
          publications.push(publication)
          const enabled = track.kind === 'audio' ? requested.current.micEnabled : requested.current.videoEnabled
          if (!enabled) await publication.mute()
        }
      }catch{if(active)setError('No se pudo publicar tu cámara o micrófono. Puedes volver a conectar.')}
    })()
    return()=>{active=false;publications.forEach(publication=>{if(publication.track)void room.localParticipant.unpublishTrack(publication.track,true)});tracks.forEach(track=>track.stop());if(published.current===tracks){published.current=[];publicationsRef.current=[]}}
  },[room,localMedia,role,source])
  useEffect(()=>{published.current.forEach(track=>{track.enabled=track.kind==='audio'?micEnabled:videoEnabled});publicationsRef.current.forEach(publication=>{const enabled=publication.kind===Track.Kind.Audio?micEnabled:videoEnabled;void(enabled?publication.unmute():publication.mute()).catch(()=>{})})},[micEnabled,videoEnabled])
  const host=participants.find(participant=>participant.id.toLowerCase()===hostId.toLowerCase())
  return { connected, error, hostStream:host?.stream||null, hostVideoEnabled:host?.videoEnabled, guests:participants.filter(participant=>participant!==host), retry:()=>setGeneration(value=>value+1) }
}
