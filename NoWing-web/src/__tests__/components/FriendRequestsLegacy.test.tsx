import { randomUUID } from 'node:crypto'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { FriendRequestsPage } from '../../features/social/FriendRequestsPage'
import { supabase } from '../../core/supabase'

jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: false }) }))
const mockUser = { id: 'legacy-owner' }
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: false }))
jest.mock('../../core/supabase', () => ({ supabase: { from: jest.fn() }, isSupabaseConfigured: true }))
jest.mock('../../core/fallbackData', () => ({ loadFallbackFriends: jest.fn(), cancelFallbackFriendRequest: jest.fn(), updateFallbackFriendRequest: jest.fn() }))
jest.mock('../../core/publicProfileSummaries', () => ({ publicProfileSummaries: async () => new Map() }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))

test('legacy acceptance keeps sent requests intact after changing tabs', async () => {
  Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: randomUUID })
  let finish!: (result: { error: null }) => void
  const pending = new Promise(resolve => { finish = resolve })
  const updateFilter = jest.fn().mockReturnValue({ eq: jest.fn().mockReturnValue(pending) })
  jest.mocked(supabase!.from).mockImplementation(() => ({
    select: () => ({ eq: (field: string) => ({ order: async () => ({ data: [{
      id: 'request', sender_id: field === 'receiver_id' ? 'sender' : mockUser.id,
      receiver_id: field === 'receiver_id' ? mockUser.id : 'recipient', status: 'pending', created_at: '2026-11-10T12:00:00Z',
    }], error: null }) }) }),
    update: () => ({ eq: updateFilter }),
  }) as ReturnType<NonNullable<typeof supabase>['from']>)
  render(<MemoryRouter><FriendRequestsPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  expect(updateFilter).toHaveBeenCalledWith('id', 'request')
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  expect(await screen.findByRole('link', { name: /Usuario de Novvin/ })).toHaveAttribute('href', '/app/profile/recipient')
  await act(async () => finish({ error: null }))
  expect(screen.getByRole('link', { name: /Usuario de Novvin/ })).toHaveAttribute('href', '/app/profile/recipient')
  expect(screen.getByRole('button', { name: 'Enviadas' })).toHaveAttribute('aria-pressed', 'true')
})

test('legacy cancellation restricts ID, sender and pending status and reports a stale request', async () => {
  Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: randomUUID })
  const filters: Array<[string, string]> = []
  const deletion = { eq: (field: string, value: string) => { filters.push([field,value]); return deletion }, select: async () => ({data:[],error:null}) }
  jest.mocked(supabase!.from).mockImplementation(() => ({
    select: () => ({ eq: (field: string) => ({ order: async () => ({data:[{
      id:'legacy-pending',sender_id: field==='sender_id'?mockUser.id:'sender',
      receiver_id: field==='sender_id'?'recipient':mockUser.id,status:'pending',created_at:'2026-11-10T12:00:00Z',
    }],error:null}) }) }),
    delete: () => deletion,
  }) as ReturnType<NonNullable<typeof supabase>['from']>)
  render(<MemoryRouter><FriendRequestsPage /></MemoryRouter>)
  await screen.findByRole('button', {name:'Aceptar'})
  fireEvent.click(screen.getByRole('button', {name:'Enviadas'}))
  fireEvent.click(await screen.findByRole('button', {name:'Cancelar solicitud'}))
  expect(await screen.findByText(/La solicitud cambió/)).toBeInTheDocument()
  expect(filters).toEqual([['id','legacy-pending'],['sender_id','legacy-owner'],['status','pending']])
  expect(screen.getByRole('button', {name:'Cancelar solicitud'})).toBeEnabled()
})

test('legacy cancellation reloads the current list after a confirmed deletion',async()=>{
  Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: randomUUID })
  let removed=false
  const deletion={eq:()=>deletion,select:async()=>{removed=true;return {data:[{id:'pending'}],error:null}}}
  jest.mocked(supabase!.from).mockImplementation(()=>({
    select:()=>({eq:(field:string)=>({order:async()=>({data:removed?[]:[{id:'pending',sender_id:field==='sender_id'?mockUser.id:'sender',receiver_id:field==='sender_id'?'recipient':mockUser.id,status:'pending'}],error:null})})}),
    delete:()=>deletion,
  }) as ReturnType<NonNullable<typeof supabase>['from']>)
  render(<MemoryRouter><FriendRequestsPage /></MemoryRouter>)
  await screen.findByRole('button',{name:'Aceptar'})
  fireEvent.click(screen.getByRole('button',{name:'Enviadas'}))
  fireEvent.click(await screen.findByRole('button',{name:'Cancelar solicitud'}))
  expect(await screen.findByText('Sin solicitudes enviadas')).toBeInTheDocument()
})
