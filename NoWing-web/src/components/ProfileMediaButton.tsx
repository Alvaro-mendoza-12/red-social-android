import { useState, type ReactNode } from 'react'
import { MediaViewer } from './MediaViewer'
import { isVideoAvatarSource } from '../core/profileMediaFields'

export function ProfileMediaButton({url,name,mediaType='',className='',children}:{url:string;name:string;mediaType?:string;className?:string;children:ReactNode}) {
  const [open,setOpen]=useState(false)
  if(!url) return <>{children}</>
  return <><button className={`profile-media-open ${className}`} type="button" aria-label={`Ampliar ${name}`} onClick={()=>setOpen(true)}>{children}</button>{open?<MediaViewer items={[{url,type:isVideoAvatarSource(url,mediaType)?'video':'image',title:name}]} onClose={()=>setOpen(false)}/>:null}</>
}
