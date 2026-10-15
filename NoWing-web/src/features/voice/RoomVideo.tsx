import { useEffect, useRef, useState } from 'react'
export function RoomVideo({ stream, label }: { stream: MediaStream; label: string }) {
  const video = useRef<HTMLVideoElement>(null), [active,setActive] = useState(false)
  useEffect(()=>{
    const tracks=stream.getVideoTracks(),update=()=>setActive(tracks.some(track=>track.readyState==='live'&&!track.muted))
    update();tracks.forEach(track=>{track.addEventListener('mute',update);track.addEventListener('unmute',update);track.addEventListener('ended',update)})
    return()=>tracks.forEach(track=>{track.removeEventListener('mute',update);track.removeEventListener('unmute',update);track.removeEventListener('ended',update)})
  },[stream])
  useEffect(()=>{const node=video.current;if(!node)return;node.srcObject=stream;void node.play().catch(()=>{});return()=>{node.pause();node.srcObject=null}},[stream])
  return <figure className="voice-video-tile" hidden={!active}><video ref={video} autoPlay muted playsInline/><span>{label}</span></figure>
}
