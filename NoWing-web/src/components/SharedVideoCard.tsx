import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../core/auth/AuthProvider'
import { loadFallbackVideo } from '../core/fallbackData'
import { profileVideoCoverSource } from '../core/profileMediaFields'
import type { SharedVideoReference } from '../core/sharedVideoReference'
import { textOf } from '../core/utils'
import { StreamingImage } from './StreamingMedia'
export function SharedVideoCard({ reference }: { reference: SharedVideoReference }) {
  const { user }=useAuth()
  const [preview,setPreview]=useState<{owner:string;id:string;cover:string;title:string}|null>(null)
  useEffect(()=>{
    let active=true
    if(user)void loadFallbackVideo(reference.videoId).then(({video})=>{if(active)setPreview({owner:user.id,id:reference.videoId,cover:profileVideoCoverSource(video),title:textOf(video,['title','description'],'Video de Novvin')})}).catch(()=>{})
    return()=>{active=false}
  },[reference.videoId,user?.id])
  const current=preview?.owner===user?.id&&preview?.id===reference.videoId?preview:null
  return <Link className="shared-video-card" to={`/app/reels/${encodeURIComponent(reference.videoId)}`} aria-label="Abrir video compartido">
    <div>{current?.cover?<StreamingImage src={current.cover} alt="Vista previa del video" loading="lazy"/>:null}<span className="shared-video-play" aria-hidden="true">▶</span></div>
    <strong>{current?.title||'Video de Novvin'}</strong>{reference.caption?<p>{reference.caption}</p>:null}
  </Link>
}
