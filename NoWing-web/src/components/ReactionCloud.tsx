import type { CSSProperties } from 'react'
import type { ReactionParticle } from './useReactionBursts'
import './reaction-cloud.css'
export function ReactionCloud({items,room=false}:{items:ReactionParticle[];room?:boolean}) {
  return <div className={`reaction-cloud${room?' reaction-cloud-room':''}`} aria-hidden="true">{items.slice(-20).map(item=>{
    const seed=[...item.id].reduce((value,char)=>(Math.imul(value,31)+char.charCodeAt(0))>>>0,7),heart=['❤️','❤','heart'].includes(item.emoji),id='reaction-'+item.id.replace(/[^a-zA-Z0-9_-]/g,'')
    const style={'--reaction-x':`${8+seed%25}%`,'--reaction-drift':`${(seed%90)-45}px`,'--reaction-rise':`${280+seed%150}px`,'--reaction-turn':`${(seed%32)-16}deg`,'--reaction-hue':`${seed%18}deg`} as CSSProperties
    return <span className="reaction-flight" key={item.id} style={style}><i className="reaction-launch-ring"/><span className={`reaction-art ${heart?'reaction-heart':item.emoji==='👏'?'reaction-clap':'reaction-emoji'}`}>{heart?<svg viewBox="0 0 48 48"><defs><linearGradient id={id} x1="0" y1="0" x2=".8" y2="1"><stop stopColor="#ffd0e6"/><stop offset=".4" stopColor="#ff558e"/><stop offset="1" stopColor="#b81364"/></linearGradient></defs><path d="M24 42C19 38 3 27 3 15C3 2 19 0 24 12C29 0 45 2 45 15C45 27 29 38 24 42Z" fill={`url(#${id})`}/><ellipse cx="12" cy="12" rx="4" ry="2" fill="#fff" opacity=".65" transform="rotate(-30 12 12)"/></svg>:item.emoji}</span><b className="reaction-spark reaction-spark-one">✦</b><b className="reaction-spark reaction-spark-two">✧</b><b className="reaction-spark reaction-spark-three">✦</b></span>
  })}</div>
}
