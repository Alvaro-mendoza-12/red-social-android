import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { RefreshCw, Trophy } from 'lucide-react'
import { useAuth } from '../../core/auth/AuthProvider'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { textOf, numberOf, friendlyError, type GenericRow } from '../../core/utils'
import { SafeAvatar } from '../../components/SafeAvatar'
import { StreamingImage } from '../../components/StreamingMedia'
import { StatusView } from '../../components/StatusView'
import './ranking.css'

type Tab = 'people' | 'videos' | 'posts'
const metrics: Record<Tab, { value: string; label: string; fields: string[] }[]> = {
  people: [{ value: 'followers', label: 'Seguidores', fields: ['followers_count','follower_count','followers'] }, { value: 'following', label: 'Siguiendo', fields: ['following_count','following'] }, { value: 'posts', label: 'Publicaciones', fields: ['post_count','posts_count','posts'] }],
  videos: [{ value: 'views', label: 'Vistas', fields: ['views','view_count'] }, { value: 'likes', label: 'Me gusta', fields: ['like_count','likes'] }, { value: 'comments', label: 'Comentarios', fields: ['comment_count','comments'] }],
  posts: [{ value: 'likes', label: 'Me gusta', fields: ['like_count','likes'] }, { value: 'comments', label: 'Comentarios', fields: ['comment_count','comments'] }, { value: 'shares', label: 'Compartidos', fields: ['share_count','shares'] }],
}

export function RankingPage() {
  const { user } = useAuth(), [tab,setTab] = useState<Tab>('people'), [metric,setMetric] = useState('followers')
  const [rows,setRows] = useState<GenericRow[]>([]), [myRank,setMyRank] = useState<number | null>(null), [loading,setLoading] = useState(true), [error,setError] = useState(''), [reload,setReload] = useState(0)
  const selected = metrics[tab].find(item => item.value === metric) || metrics[tab][0]
  const accountId = user?.id
  useEffect(() => {
    if (!accountId) return
    const controller = new AbortController()
    const path = tab === 'people' ? '/v1/data/profiles/ranking?metric=' + selected.value + '&limit=100' : tab === 'videos' ? '/v1/data/videos?limit=100&offset=0' : '/v1/data/feed?limit=100'
    void Promise.resolve().then(() => {
      if (controller.signal.aborted) return null
      setRows([]); setMyRank(null); setLoading(true); setError('')
      return fallbackApiJson<{items:GenericRow[];myRank?:number}>(path,{expectedAccountId:accountId,signal:controller.signal})
    }).then(response => {
      if (!response) return
      if (controller.signal.aborted) return
      const items = response.items || []
      setRows(tab === 'people' ? items : [...items].sort((a,b) => numberOf(b,selected.fields,0) - numberOf(a,selected.fields,0)))
      setMyRank(response.myRank || null)
    }).catch(caught => { if (!controller.signal.aborted) setError(friendlyError(caught,'No se pudo cargar el ranking.')) }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  },[accountId,tab,selected.value,selected.fields,reload])
  return <section className="ranking-page section-stack">
    <header className="ranking-heading"><div><Trophy size={26}/><div><h2>Ranking de Novvin</h2><p>Descubre personas y contenido de la comunidad.</p></div></div><button className="icon-btn" type="button" disabled={loading} aria-label="Actualizar ranking" onClick={() => setReload(value => value+1)}><RefreshCw size={19}/></button></header>
    <nav className="ranking-tabs" aria-label="Tipo de ranking">{([['people','Personas'],['videos','Videos'],['posts','Publicaciones']] as const).map(([value,label]) => <button className="chip-btn" type="button" key={value} aria-pressed={tab===value} onClick={() => { setTab(value);setMetric(metrics[value][0].value) }}>{label}</button>)}</nav>
    <div className="row-between"><label className="ranking-order">Ordenar por<select className="text-input" aria-label="Ordenar ranking" value={selected.value} onChange={event => setMetric(event.target.value)}>{metrics[tab].map(item => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>{tab==='people'&&myRank?<p className="ranking-my-position">Tu posición <strong>#{myRank}</strong></p>:null}</div>
    {error ? <div role="alert" className="error-text">{error}<button className="ghost-btn" type="button" onClick={() => setReload(value => value+1)}>Reintentar</button></div> : loading ? <StatusView title="Cargando ranking" description="Consultando la actividad de la comunidad…"/> : !rows.length ? <StatusView title="Todavía no hay resultados" description="La actividad de la comunidad aparecerá aquí."/> : <ol className={'ranking-results '+(tab==='people'?'people':'content')}>
      {rows.map((row,index) => {
        const id=textOf(row,['id']),name=textOf(row,tab==='people'?['display_name','username']:['user_name','owner_name','author_name'],'Usuario de Novvin')
        const path=tab==='people'?'/app/profile/'+id:tab==='videos'?'/app/reels/'+id:'/app/feed/post/'+id
        const image=textOf(row,tab==='videos'?['cover_url','thumbnail_url']:['media_url','image_url'])
        return <li key={id}><Link to={path}><span className="ranking-position" data-podium={index<3}>{index+1}</span>{tab==='people'?<SafeAvatar url={textOf(row,['avatar_url','profile_image'])} name={name} mediaType={textOf(row,['avatar_media_type'])}/>:image?<StreamingImage className="ranking-cover" src={image} alt=""/>:<span className="ranking-cover placeholder"><Trophy size={22}/></span>}<div className="ranking-copy"><strong>{tab==='people'?name:textOf(row,['title','content','description'],'Publicación')}</strong><small>{tab==='people'?textOf(row,['username']):name}</small></div><span className="ranking-score"><strong>{numberOf(row,selected.fields,0).toLocaleString('es')}</strong><small>{selected.label}</small></span></Link></li>
      })}
    </ol>}
    <Link className="ghost-btn" to="/app/challenges/ranking">Ver ranking de rondas y retos</Link>
  </section>
}
