import { lazy, Suspense, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from '../../core/auth/AuthProvider'
import { StatusView } from '../../components/StatusView'

const Room = lazy(() => import('./VoiceRoomPage').then(module => ({ default: module.VoiceRoomPage })))

/** Route changes hide the console without disposing its microphone or peers. */
export function VoiceRoomHost() {
  const { user } = useAuth()
  return user ? <AccountVoiceRoomHost key={user.id} /> : null
}
function AccountVoiceRoomHost() {
  const location = useLocation()
  const routeRoom = /^\/app\/voice\/([^/]+)\/?$/.exec(location.pathname)?.[1] || ''
  const [state, setState] = useState({ route: routeRoom, roomId: routeRoom })
  if (routeRoom !== state.route) setState({ route: routeRoom, roomId: routeRoom ? decodeURIComponent(routeRoom) : state.roomId })
  return state.roomId ? <Suspense fallback={routeRoom ? <StatusView title="Cargando sala de voz" description="Preparando la sala…" /> : null}><Room key={state.roomId} roomIdOverride={state.roomId} minimized={!routeRoom} onExit={() => setState(current => ({ ...current, roomId: '' }))} /></Suspense> : null
}
