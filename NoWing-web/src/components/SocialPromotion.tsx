import {useEffect,useState} from 'react'
import {useAuth} from '../core/auth/AuthProvider'
import {fallbackApiJson} from '../core/auth/fallbackAuth'
import {PromotionCarousel,type PromotionCatalog} from './PromotionCarousel'
import {PromotionEditor} from './PromotionEditor'
import './communityQuestion.css'
import {promotionCatalogEvent} from '../core/promotionCatalogEvents'
export function SocialPromotion({manageOpen=false,onClose=()=>{},onManageAccess,showAd=true}:{showAd?:boolean;manageOpen?:boolean;onClose?:()=>void;onManageAccess?:(value:boolean)=>void}) {
  const {user}=useAuth()
  return user?<PromotionBody key={user.id} accountId={user.id} showAd={showAd} manageOpen={manageOpen} onClose={onClose} onManageAccess={onManageAccess}/>:null
}
function PromotionBody({accountId,manageOpen,onClose,onManageAccess,showAd}:{showAd:boolean;accountId:string;manageOpen:boolean;onClose:()=>void;onManageAccess?: (value:boolean)=>void}) {
  const [catalog,setCatalog]=useState<PromotionCatalog|null>(null),[hidden,setHidden]=useState<string[]>([])
  useEffect(()=>{
    let active=true,request=0
    const load=()=>{const current=++request;void fallbackApiJson<PromotionCatalog>('/v1/data/social/promotions').then(value=>{if(active&&current===request){setCatalog(value);onManageAccess?.(value.canManage)}}).catch(()=>{})}
    const changed=(event:Event)=>{if((event as CustomEvent<string>).detail===accountId)load()}
    load();window.addEventListener(promotionCatalogEvent,changed)
    return()=>{active=false;window.removeEventListener(promotionCatalogEvent,changed)}
  },[accountId,onManageAccess])
  const ad=catalog?.items.find(item=>item.active&&!hidden.includes(item.id))
  return <>{showAd&&ad?<article className="social-promotion surface-card" aria-label="Publicidad de Novvin"><header className="row-between"><div className="row-actions"><img src="/favicon.ico" width={36} height={36} alt="Novvin"/><span><strong>Novvin</strong><small>Publicidad · De la aplicación</small></span></div><button className="icon-btn" aria-label="Ocultar anuncio" onClick={()=>setHidden([...hidden,ad.id])}>×</button></header><PromotionCarousel key={ad.id} ad={ad}/></article>:null}{manageOpen&&catalog?.canManage?<PromotionEditor accountId={accountId} catalog={catalog} onRefresh={setCatalog} onClose={onClose}/>:null}</>
}
