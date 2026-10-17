import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronLeft, ChevronRight, Plus, Trash2, ArrowUp, ArrowDown } from 'lucide-react'
import { StreamingImage } from './StreamingMedia'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { uploadFallbackFile } from '../core/fallbackData'

export type PromotionCard = { title: string; description: string; imageUrl: string; destination: string; cta: string; file?: File }
export type Promotion = PromotionCard & { id: string; active: boolean; cards?: PromotionCard[] }
export type PromotionCatalog = { items: Promotion[]; canManage: boolean }
type DraftCard = PromotionCard & { file?: File; key: string }
const emptyCard = (): DraftCard => ({ key: crypto.randomUUID(), title: '', description: '', imageUrl: '', destination: '/app/messages', cta: 'Descubrir' })
function LocalImage({file,alt}:{file:File;alt:string}) { const ref=useRef<HTMLImageElement>(null);useEffect(()=>{const url=URL.createObjectURL(file);if(ref.current)ref.current.src=url;return()=>URL.revokeObjectURL(url)},[file]);return <img ref={ref} alt={alt}/> }
export function PromotionCarousel({ad,preview=false}:{ad:Promotion;preview?:boolean}) {
  const rail=useRef<HTMLDivElement>(null), [page,setPage]=useState(0)
  const cards=ad.cards?.length?ad.cards:[ad]
  const move=(delta:number)=>{const node=rail.current;if(node)node.scrollBy({left:delta*((node.firstElementChild?.getBoundingClientRect().width || node.clientWidth)+12),behavior:'smooth'})}
  return <article className="promotion-creative" aria-label={preview?'Vista previa del anuncio':'Anuncio de Novvin'}>
    <h3>{ad.title || 'Título de tu anuncio'}</h3><p>{ad.description}</p>
    <div className="promotion-rail" ref={rail} onScroll={()=>{const node=rail.current;if(node)setPage(Math.round(node.scrollLeft/((node.firstElementChild?.getBoundingClientRect().width || node.clientWidth)+12)))}} aria-label="Tarjetas del anuncio" tabIndex={0}>
      {cards.map((card,index)=><section className="promotion-product" key={index} aria-label={`Tarjeta ${index+1} de ${cards.length}`}>
        {card.file?<LocalImage file={card.file} alt={card.title}/>:card.imageUrl?<StreamingImage src={card.imageUrl} alt={card.title} loading="lazy"/>:<div className="promotion-placeholder">Novvin</div>}
        <div className="promotion-product-info"><div><strong>{card.title || 'Título de la tarjeta'}</strong><small>{card.description}</small></div>{preview?<span className="primary-btn">{card.cta}</span>:card.destination.startsWith('/')?<Link className="primary-btn" to={card.destination}>{card.cta}</Link>:<a className="primary-btn" href={card.destination} target="_blank" rel="noopener noreferrer">{card.cta}</a>}</div>
      </section>)}
    </div>
    {cards.length>1?<nav className="promotion-navigation" aria-label="Navegar anuncio"><button className="icon-btn" aria-label="Tarjeta anterior" disabled={page===0} onClick={()=>move(-1)}><ChevronLeft/></button><span>{Math.min(page+1,cards.length)} / {cards.length} · Desliza para ver más</span><button className="icon-btn" aria-label="Tarjeta siguiente" disabled={page>=cards.length-1} onClick={()=>move(1)}><ChevronRight/></button></nav>:null}
  </article>
}
export function PromotionEditor({catalog,accountId,onRefresh,onClose}:{catalog:PromotionCatalog;accountId:string;onRefresh:(value:PromotionCatalog)=>void;onClose:()=>void}) {
  const [editing,setEditing]=useState<Promotion|null>(null),[title,setTitle]=useState(''),[description,setDescription]=useState(''),[cards,setCards]=useState<DraftCard[]>([emptyCard()]),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('')
  const patch=(key:string,value:Partial<DraftCard>)=>setCards(old=>old.map(card=>card.key===key?{...card,...value}:card))
  const reset=()=>{setEditing(null);setTitle('');setDescription('');setCards([emptyCard()])}
  const refresh=async()=>onRefresh(await fallbackApiJson<PromotionCatalog>('/v1/data/social/promotions?manage=1'))
  useEffect(()=>{let active=true;void fallbackApiJson<PromotionCatalog>('/v1/data/social/promotions?manage=1').then(value=>{if(active)onRefresh(value)}).catch(()=>{if(active)setError('No se pudieron cargar tus anuncios.')});return()=>{active=false}},[onRefresh])
  return <div className="app-dialog-backdrop"><section className="surface-card promotion-manager" role="dialog" aria-modal="true" aria-label="Publicidad" onKeyDown={event=>{if(event.key==='Escape'&&!busy)onClose()}}>
    <header className="row-between"><div><h2>Publicidad</h2><p>Crea anuncios de Novvin con hasta 10 tarjetas.</p></div><button type="button" className="icon-btn" aria-label="Cerrar publicidad" disabled={busy} onClick={onClose}>×</button></header>
    <div className="promotion-manager-content"><form onSubmit={async event=>{event.preventDefault();setError('');setNotice('');setBusy(true);try{
      const uploaded:PromotionCard[]=[]
      for(const card of cards){let imageUrl=card.imageUrl;if(card.file)imageUrl=(await uploadFallbackFile('post-media',card.file,undefined,accountId)).publicUrl;if(!imageUrl)throw Error('Añade una imagen a cada tarjeta.');uploaded.push({title:card.title,description:card.description,imageUrl,destination:card.destination,cta:card.cta})}
      await fallbackApiJson('/v1/data/social/promotions'+(editing?'/'+editing.id:''),{method:editing?'PATCH':'POST',body:JSON.stringify({title,description,cards:uploaded,destination:uploaded[0].destination,cta:uploaded[0].cta})});await refresh();reset();setNotice('Anuncio guardado. Ya puedes verlo en Social.')
    }catch(caught){setError(caught instanceof Error&&caught.message==='Añade una imagen a cada tarjeta.'?caught.message:'No se pudo guardar. Revisa las imágenes y usa un enlace https:// o un destino de Novvin.')}finally{setBusy(false)}}}>
      <h3>{editing?'Editar anuncio':'Crear anuncio'}</h3><label>Título del anuncio<input required className="text-input" maxLength={100} value={title} disabled={busy} onChange={e=>setTitle(e.target.value)}/></label><label>Texto principal<textarea className="text-input" maxLength={500} value={description} disabled={busy} onChange={e=>setDescription(e.target.value)}/></label>
      {cards.map((card,index)=><fieldset className="promotion-card-editor" disabled={busy} key={card.key}><legend>Tarjeta {index+1}</legend><div className="row-between"><label className="promotion-image-picker">{card.file?<LocalImage file={card.file} alt="Vista previa de la tarjeta"/>:card.imageUrl?<StreamingImage src={card.imageUrl} alt="Vista previa de la tarjeta"/>:<span>Añadir imagen</span>}<input type="file" aria-label={`Imagen de tarjeta ${index+1}`} accept="image/png,image/jpeg,image/webp" onChange={e=>{const file=e.target.files?.[0];if(!file)return;if(!['image/png','image/jpeg','image/webp'].includes(file.type)||file.size>16*1024*1024){setError('Elige una imagen JPG, PNG o WebP de hasta 16 MB.');return}patch(card.key,{file});setError('')}}/></label><div className="row-actions"><button type="button" className="icon-btn" aria-label={`Subir tarjeta ${index+1}`} disabled={index===0} onClick={()=>setCards(old=>{const next=[...old];[next[index-1],next[index]]=[next[index],next[index-1]];return next})}><ArrowUp/></button><button type="button" className="icon-btn" aria-label={`Bajar tarjeta ${index+1}`} disabled={index===cards.length-1} onClick={()=>setCards(old=>{const next=[...old];[next[index+1],next[index]]=[next[index],next[index+1]];return next})}><ArrowDown/></button><button type="button" className="icon-btn" aria-label={`Eliminar tarjeta ${index+1}`} disabled={cards.length===1} onClick={()=>setCards(old=>old.filter(item=>item.key!==card.key))}><Trash2/></button></div></div>
        <label>Título de tarjeta<input required className="text-input" maxLength={100} value={card.title} onChange={e=>patch(card.key,{title:e.target.value})}/></label><label>Descripción breve<input className="text-input" maxLength={500} value={card.description} onChange={e=>patch(card.key,{description:e.target.value})}/></label><label>Destino<input required className="text-input" placeholder="https://tu-sitio.com/producto o /app/messages" maxLength={2000} value={card.destination} onChange={e=>patch(card.key,{destination:e.target.value})}/></label><label>Botón<input required className="text-input" maxLength={30} value={card.cta} onChange={e=>patch(card.key,{cta:e.target.value})}/></label>
      </fieldset>)}
      <button className="ghost-btn" type="button" disabled={busy||cards.length>=10} onClick={()=>setCards([...cards,emptyCard()])}><Plus size={18}/> Añadir tarjeta ({cards.length}/10)</button>{error?<p role="alert" className="error-text">{error}</p>:null}{notice?<p role="status">{notice}</p>:null}<div className="row-actions"><button className="primary-btn" disabled={busy}>{busy?'Subiendo imágenes y guardando…':editing?'Guardar cambios':'Publicar anuncio'}</button>{editing?<button type="button" className="ghost-btn" disabled={busy} onClick={reset}>Cancelar edición</button>:null}</div>
    </form><aside><h3>Vista previa</h3><PromotionDraftPreview title={title} description={description} cards={cards}/><h3>Mis anuncios</h3>{catalog.items.filter(ad=>ad.id!=='novvin-welcome').map(ad=><section className="promotion-saved" key={ad.id}><strong>{ad.title}</strong><small>{ad.active?'Activo':'Pausado'} · {ad.cards?.length || 1} tarjeta(s)</small><div className="row-actions"><button className="mini-action-btn" disabled={busy} onClick={()=>{setEditing(ad);setTitle(ad.title);setDescription(ad.description);setCards((ad.cards?.length?ad.cards:[ad]).map(card=>({...card,key:crypto.randomUUID()})))}}>Editar</button><button className="mini-action-btn" disabled={busy} onClick={async()=>{setBusy(true);try{await fallbackApiJson('/v1/data/social/promotions/'+ad.id,{method:'PATCH',body:JSON.stringify({active:!ad.active})});await refresh()}catch{setError('No se pudo cambiar el estado.')}finally{setBusy(false)}}}>{ad.active?'Pausar':'Activar'}</button></div></section>)}</aside></div>
  </section></div>
}
function PromotionDraftPreview({title,description,cards}:{title:string;description:string;cards:DraftCard[]}) {
  return <PromotionCarousel preview ad={{id:'preview',title,description,imageUrl:'',destination:'/app/messages',cta:'Descubrir',active:true,cards}}/>
}
