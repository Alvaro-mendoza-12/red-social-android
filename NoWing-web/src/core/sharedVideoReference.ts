export type SharedVideoReference = { videoId: string; caption: string }
export function sharedVideoReference(content: string): SharedVideoReference | null {
  const matches=[...content.matchAll(/(?:https?:\/\/|conectate:\/\/)\S+/gi)]
  if(matches.length!==1)return null
  const match=matches[0]
  try {
    const url=new URL(match[0]);if(url.username||url.password)return null
    const parts=url.pathname.split('/').filter(Boolean)
    let videoId=''
    if(url.protocol==='conectate:'&&url.hostname==='video'&&parts.length===1)videoId=parts[0]
    else if(['http:','https:'].includes(url.protocol)&&!url.port){
      if(url.protocol==='https:'&&['novvinapp.com','www.novvinapp.com','red-social-android.vercel.app'].includes(url.hostname)&&parts.length===3&&parts[0]==='app'&&parts[1]==='reels')videoId=parts[2]
      if(['conectate.com','www.conectate.com'].includes(url.hostname)&&parts.length===2&&parts[0]==='video')videoId=parts[1]
    }
    if(!/^[A-Za-z0-9_-]{8,100}$/.test(videoId))return null
    const before=content.slice(0,match.index).trim().replace(/^(¡Mira este video!|Mira este video en Novvin:)/,'').trim()
    const after=content.slice((match.index||0)+match[0].length).trim()
    return {videoId,caption:[before,after].filter(Boolean).join('\n').slice(0,800)}
  }catch{return null}
}
