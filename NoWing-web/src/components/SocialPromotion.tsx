import {useEffect,useState} from 'react'
import {useAuth} from '../core/auth/AuthProvider'
import {fallbackApiJson} from '../core/auth/fallbackAuth'
import {PromotionCarousel,PromotionEditor,type PromotionCatalog} from './PromotionCarousel'
import './communityQuestion.css'
export function SocialPromotion({manageOpen=false,onClose=()=>{},onManageAccess}:{manageOpen?:boolean;onClose?:()=>void;onManageAccess?:(value:boolean)=>void}) {
  const {user}=useAuth()
  return user?<PromotionBody key={user.id} accountId={user.id} manageOpen={manageOpen} onClose={onClose} onManageAccess={onManageAccess}/>:null
}
function PromotionBody({accountId,manageOpen,onClose,onManageAccess}:{accountId:string;manageOpen:boolean;onClose:()=>void;onManageAccess?: (value:boolean)=>void}) {
  const [catalog,setCatalog]=useState<PromotionCatalog|null>(null),[hidden,setHidden]=useState<string[]>([])
  useEffect(()=>{let active=true;void fallbackApiJson<PromotionCatalog>('/v1/data/social/promotions').then(value=>{if(active){setCatalog(value);onManageAccess?.(value.canManage)}}).catch(()=>{});return()=>{active=false}},[onManageAccess])
  const ad=catalog?.items.find(item=>item.active&&!hidden.includes(item.id))
  return <>{ad?<article className="social-promotion surface-card" aria-label="Publicidad de Novvin"><header className="row-between"><div className="row-actions"><img src="/favicon.ico" width={36} height={36} alt="Novvin"/><span><strong>Novvin</strong><small>Publicidad · De la aplicación</small></span></div><button className="icon-btn" aria-label="Ocultar anuncio" onClick={()=>setHidden([...hidden,ad.id])}>×</button></header><PromotionCarousel key={ad.id} ad={ad}/></article>:null}{manageOpen&&catalog?.canManage?<PromotionEditor accountId={accountId} catalog={catalog} onRefresh={setCatalog} onClose={onClose}/>:null}</>
}
