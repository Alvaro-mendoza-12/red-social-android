import {useEffect,useRef,type ReactNode} from 'react'
import {MoreHorizontal, SmilePlus} from 'lucide-react'

let mountedMenus=0
function closeOutside(event:PointerEvent){document.querySelectorAll<HTMLDetailsElement>('details.message-menu[open]').forEach(menu=>{if(!menu.contains(event.target as Node))menu.removeAttribute('open')})}
function closeOnEscape(event:KeyboardEvent){if(event.key==='Escape')document.querySelectorAll<HTMLDetailsElement>('details.message-menu[open]').forEach(menu=>{menu.removeAttribute('open');menu.querySelector('summary')?.focus()})}

function positionMessageMenu(menu: HTMLDetailsElement) {
  document.querySelectorAll<HTMLDetailsElement>('details.message-menu[open]').forEach(other=>{if(other!==menu)other.removeAttribute('open')})
  const popover=menu.querySelector<HTMLElement>('.message-menu-popover')
  if(!popover)return
  popover.style.transform=''
  requestAnimationFrame(()=>{
    if(!menu.open)return
    const area=menu.closest('.chat-thread')?.getBoundingClientRect()
    const top=Math.max(8,(area?.top||0)+8),bottom=Math.min(area?.bottom||innerHeight,innerHeight)-8
    const left=Math.max(8,(area?.left||0)+8),right=Math.min(innerWidth-8,(area?.right||innerWidth)-8)
    popover.style.maxHeight=Math.max(100,bottom-top)+'px'
    popover.style.minWidth='0';popover.style.width='236px';popover.style.maxWidth=Math.max(0,right-left)+'px'
    const rect=popover.getBoundingClientRect(),anchor=menu.getBoundingClientRect()
    const dx=rect.left<left?left-rect.left:rect.right>right?right-rect.right:0
    const dy=rect.top<top?top-rect.top:rect.bottom>bottom?Math.max(top-rect.top,anchor.top-rect.height-6-rect.top):0
    popover.style.transform=`translate(${dx}px,${dy}px)`
  })
}

export function MessageOptionsMenu({mine,children,onReact}:{mine:boolean;children:ReactNode;onReact?:(emoji:string)=>void}){
  const ref=useRef<HTMLDetailsElement>(null)
  useEffect(()=>{
    if(mountedMenus++===0){document.addEventListener('pointerdown',closeOutside);document.addEventListener('keydown',closeOnEscape)}
    return()=>{if(--mountedMenus===0){document.removeEventListener('pointerdown',closeOutside);document.removeEventListener('keydown',closeOnEscape)}}
  },[])
  return <>{onReact ? <MessageQuickReaction mine={mine} onReact={onReact}/> : null}<details ref={ref} name="novvin-message-options" className={mine?'message-menu message-menu-mine':'message-menu'} onToggle={event=>{if(event.currentTarget.open)positionMessageMenu(event.currentTarget)}} onClick={event=>{if((event.target as Element).closest('button'))ref.current?.removeAttribute('open')}}>
    <summary title="Opciones del mensaje" aria-label="Opciones del mensaje"><MoreHorizontal aria-hidden="true"/></summary>
    <div className="message-menu-popover">{children}</div>
  </details></>
}
export function MessageQuickReaction({mine,onReact}:{mine:boolean;onReact:(emoji:string)=>void}) {
  const ref=useRef<HTMLDetailsElement>(null)
  useEffect(()=>{
    if(mountedMenus++===0){document.addEventListener('pointerdown',closeOutside);document.addEventListener('keydown',closeOnEscape)}
    return()=>{if(--mountedMenus===0){document.removeEventListener('pointerdown',closeOutside);document.removeEventListener('keydown',closeOnEscape)}}
  },[])
  return <details ref={ref} name="novvin-message-options" className={`message-menu message-quick-reaction${mine?' message-quick-reaction-mine':''}`} onToggle={event=>{if(event.currentTarget.open)positionMessageMenu(event.currentTarget)}}>
    <summary aria-label="Reaccionar al mensaje" title="Reaccionar al mensaje"><SmilePlus aria-hidden="true"/></summary>
    <div className="message-menu-popover"><MessageReactionBar onReact={emoji=>{ref.current?.removeAttribute('open');ref.current?.querySelector('summary')?.focus();onReact(emoji)}}/></div>
  </details>
}
const reactions=[['👍','Me gusta'],['❤️','Me encanta'],['😂','Me divierte'],['😮','Me sorprende'],['😢','Me entristece'],['🙏','Gracias']]
export function MessageReactionBar({onReact}:{onReact:(emoji:string)=>void}){
  return <div className="message-reaction-bar" role="group" aria-label="Reacciones rápidas">{reactions.map(([emoji,name])=><button key={emoji} type="button" title={name} aria-label={`Reaccionar con ${emoji}`} onClick={()=>onReact(emoji)}>{emoji}</button>)}</div>
}
