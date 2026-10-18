import { lazy, Suspense, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../core/auth/AuthProvider'
const Live = lazy(() => import('./LiveSessionPage').then(module => ({ default: module.LiveSessionPage })))
export function LiveSessionHost() {
  const { user } = useAuth()
  return user ? <AccountLiveSessionHost key={user.id}/> : null
}
function AccountLiveSessionHost() {
  const location = useLocation(), navigate = useNavigate()
  const route = /^\/app\/live\/([^/]+)\/?$/.exec(location.pathname)?.[1] || ''
  const [state, setState] = useState({ route, id: route ? decodeURIComponent(route) : '', search: location.search })
  if (route !== state.route) setState({ ...state, route, ...(route && !state.id ? { id: decodeURIComponent(route), search: location.search } : {}) })
  const anotherLive = Boolean(route && decodeURIComponent(route) !== state.id)
  return <>{anotherLive && state.id ? <aside className="media-session-conflict" role="status"><strong>Ya tienes otro directo abierto</strong><p>Vuelve al directo actual para salir o finalizarlo antes de abrir otro.</p><button className="primary-btn" onClick={() => navigate(`/app/live/${encodeURIComponent(state.id)}${state.search}`)}>Volver al directo</button></aside> : null}{state.id ? <Suspense fallback={null}><Live key={state.id} liveIdOverride={state.id} initialSearch={state.search} minimized={!route || anotherLive} onExit={() => setState(current => ({ ...current, id: '' }))}/></Suspense> : null}</>
}
