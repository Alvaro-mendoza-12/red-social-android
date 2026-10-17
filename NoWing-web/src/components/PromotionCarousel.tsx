import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { StreamingImage } from './StreamingMedia'

export type PromotionCard = { title: string; description: string; imageUrl: string; destination: string; cta: string; file?: File }
export type Promotion = PromotionCard & { id: string; active: boolean; cards?: PromotionCard[] }
export type PromotionCatalog = { items: Promotion[]; canManage: boolean }
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
