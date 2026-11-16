import { randomUUID } from 'node:crypto'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { FeedPage } from '../../pages/feed/FeedPage'
import { loadFallbackFeed, setFallbackPostReaction } from '../../core/fallbackData'

const mockUser = { id: 'gallery-reactions-owner', user_metadata: {} }
let mockRefresh: () => void
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: true }) }))
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true }))
jest.mock('../../core/fallbackData', () => ({ loadFallbackFeed: jest.fn(), setFallbackPostReaction: jest.fn() }))
jest.mock('../../core/ubuntuRealtime', () => ({ subscribeUbuntuRealtime: ({onEvent}: {onEvent: () => void}) => { mockRefresh=onEvent; return () => {} } }))
jest.mock('../../core/supabase', () => ({ supabase: null, resolveSupabasePublicUrl: (_b: string, url: string) => url }))
jest.mock('../../core/localCache', () => ({ readCachedValue: () => null, writeCachedValue: () => {} }))
jest.mock('../../core/webPreferences', () => ({ useWebPreferences: () => [{}] }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: ({src,alt}: {src: string;alt: string}) => <img src={src} alt={alt}/>, StreamingVideo: () => null }))
jest.mock('../../components/MediaViewer', () => ({ MediaViewer: ({items,initialIndex}: {items: unknown[];initialIndex: number}) => <div role="dialog" aria-label="Visor de prueba">{items.length} fotos, índice {initialIndex}</div> }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))
jest.mock('../../components/SocialPromotion', () => ({ SocialPromotion: () => null }))
jest.mock('../../ui/novvin/SocialMoments', () => ({ SocialMoments: () => null }))

const post = (count = 5) => ({id:'post',user_id:'author',user_name:'Autor ficticio',content:'Publicación de prueba',media_urls:Array.from({length:count},(_,i)=>`https://example.invalid/photo-${i}.png`),like_count:2,liked_by_me:false,reaction_counts:{LIKE:2},my_reaction:null})
const mount = () => render(<MemoryRouter><FeedPage /></MemoryRouter>)
beforeEach(() => {
  jest.resetAllMocks()
  mockUser.id = 'gallery-reactions-owner'
  Object.defineProperty(crypto,'randomUUID',{configurable:true,value:randomUUID})
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open','') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  jest.mocked(loadFallbackFeed).mockResolvedValue({items:[post()],hasMore:false})
})

test('a five-photo post has at most four feed thumbnails and opens every original in the viewer', async () => {
  mount()
  await screen.findByText('Publicación de prueba')
  expect(screen.getAllByRole('img')).toHaveLength(4)
  fireEvent.click(screen.getByRole('button',{name:'Ver 1 foto más'}))
  expect(screen.getByRole('dialog',{name:'Visor de prueba'})).toHaveTextContent('5 fotos, índice 4')
})

test('web offers all eight existing reactions and sends the selected reaction with the account guard', async () => {
  jest.mocked(setFallbackPostReaction).mockResolvedValue({ok:true,post:{...post(),my_reaction:'LOVE',liked_by_me:true,like_count:3,reaction_counts:{LIKE:2,LOVE:1}}})
  mount()
  await screen.findByText('Publicación de prueba')
  fireEvent.click(screen.getByRole('button',{name:'Más reacciones'}))
  const dialog = screen.getByRole('dialog',{name:'Reaccionar a la publicación'})
  expect(dialog.querySelectorAll('[data-reaction]')).toHaveLength(8)
  fireEvent.click(screen.getByRole('button',{name:'Me encanta',exact:true}))
  await waitFor(()=>expect(setFallbackPostReaction).toHaveBeenCalledWith('post',true,'LOVE','gallery-reactions-owner'))
})

test('switching a selected reaction keeps one vote, and removing it uses DELETE semantics', async () => {
  const selected = {...post(1),my_reaction:'LOVE',liked_by_me:true,like_count:3,reaction_counts:{LIKE:2,LOVE:1}}
  jest.mocked(loadFallbackFeed).mockResolvedValue({items:[selected]})
  jest.mocked(setFallbackPostReaction).mockImplementation(()=>new Promise(()=>{}))
  mount()
  await screen.findByText('Publicación de prueba')
  fireEvent.click(screen.getByRole('button',{name:'Más reacciones'}))
  fireEvent.click(screen.getByRole('button',{name:'Fuego',exact:true}))
  expect(screen.getByRole('button',{name:'Fuego',exact:true})).toHaveAttribute('aria-pressed','true')
  expect(screen.getByLabelText('3 reacciones')).toBeInTheDocument()
  expect(setFallbackPostReaction).toHaveBeenCalledWith('post',true,'FIRE','gallery-reactions-owner')
})

test('removing LOVE reduces the total once and does not replace it with LIKE', async () => {
  jest.mocked(loadFallbackFeed).mockResolvedValue({items:[{...post(),my_reaction:'LOVE',liked_by_me:true,like_count:3,reaction_counts:{LIKE:2,LOVE:1}}]})
  jest.mocked(setFallbackPostReaction).mockImplementation(()=>new Promise(()=>{}))
  mount()
  fireEvent.click(await screen.findByRole('button',{name:'Me encanta',exact:true}))
  expect(setFallbackPostReaction).toHaveBeenCalledWith('post',false,'LIKE','gallery-reactions-owner')
  expect(screen.getByRole('button',{name:'Me gusta',exact:true})).toHaveAttribute('aria-pressed','false')
  expect(screen.getByLabelText('2 reacciones')).toBeInTheDocument()
})

test('realtime refresh preserves a pending selected type and double click does not send twice', async () => {
  jest.mocked(setFallbackPostReaction).mockImplementation(()=>new Promise(()=>{}))
  mount()
  await screen.findByText('Publicación de prueba')
  fireEvent.click(screen.getByRole('button',{name:'Más reacciones'}))
  fireEvent.click(screen.getByRole('button',{name:'Me encanta',exact:true}))
  fireEvent.click(screen.getByRole('button',{name:'Me encanta',exact:true}))
  act(()=>mockRefresh())
  await waitFor(()=>expect(loadFallbackFeed).toHaveBeenCalledTimes(2))
  expect(screen.getByRole('button',{name:'Me encanta',exact:true})).toHaveAttribute('aria-pressed','true')
  expect(screen.getByLabelText('3 reacciones')).toBeInTheDocument()
  expect(setFallbackPostReaction).toHaveBeenCalledTimes(1)
})

test.each(['success','failure','return to same account'])('an old reaction %s cannot change another feed visit or hold its buttons busy', async outcome => {
  let finish!: () => void
  jest.mocked(setFallbackPostReaction).mockImplementationOnce(()=>new Promise((resolve,reject)=>{
    finish=()=>outcome==='failure'?reject(new Error('Error de la visita anterior')):resolve({ok:true,post:{...post(),my_reaction:'LOVE',liked_by_me:true}})
  }))
  const {rerender} = mount()
  await screen.findByText('Publicación de prueba')
  fireEvent.click(screen.getByRole('button',{name:'Más reacciones'}))
  fireEvent.click(screen.getByRole('button',{name:'Me encanta',exact:true}))
  mockUser.id='account-b'
  jest.mocked(loadFallbackFeed).mockResolvedValue({items:[{...post(),content:'Publicación de B'}]})
  rerender(<MemoryRouter><FeedPage/></MemoryRouter>)
  await screen.findByText('Publicación de B')
  if(outcome==='return to same account') {
    mockUser.id='gallery-reactions-owner'
    jest.mocked(loadFallbackFeed).mockResolvedValue({items:[{...post(),content:'Nueva visita de A'}]})
    rerender(<MemoryRouter><FeedPage/></MemoryRouter>)
    await screen.findByText('Nueva visita de A')
  }
  expect(screen.getByRole('button',{name:'Me gusta',exact:true})).toBeEnabled()
  await act(async()=>finish())
  expect(screen.queryByRole('button',{name:'Me encanta',exact:true})).not.toBeInTheDocument()
  expect(screen.queryByText('Error de la visita anterior')).not.toBeInTheDocument()
  expect(screen.getByLabelText('2 reacciones')).toBeInTheDocument()
})

test('a read started before the reaction ACK cannot restore the preceding selection', async () => {
  let oldRead!: (value: unknown) => void, confirm!: (value: {ok: boolean;post: ReturnType<typeof post> & {my_reaction: string}}) => void
  const accepted = {...post(),my_reaction:'LOVE',liked_by_me:true,like_count:3,reaction_counts:{LIKE:2,LOVE:1}}
  jest.mocked(setFallbackPostReaction).mockImplementationOnce(()=>new Promise(resolve=>{confirm=resolve}))
  mount()
  await screen.findByText('Publicación de prueba')
  jest.mocked(loadFallbackFeed).mockImplementationOnce(()=>new Promise(resolve=>{oldRead=resolve}))
  act(()=>mockRefresh())
  await waitFor(()=>expect(loadFallbackFeed).toHaveBeenCalledTimes(2))
  fireEvent.click(screen.getByRole('button',{name:'Más reacciones'}))
  fireEvent.click(screen.getByRole('button',{name:'Me encanta',exact:true}))
  jest.mocked(loadFallbackFeed).mockResolvedValue({items:[accepted]})
  await act(async()=>confirm({ok:true,post:accepted}))
  await waitFor(()=>expect(loadFallbackFeed).toHaveBeenCalledTimes(3))
  await act(async()=>oldRead({items:[post()]}))
  expect(screen.getByRole('button',{name:'Me encanta',exact:true})).toHaveAttribute('aria-pressed','true')
  expect(screen.getByLabelText('3 reacciones')).toBeInTheDocument()
})

test('a failed write restores the previous selection and keeps a specific error visible after refresh', async () => {
  jest.mocked(setFallbackPostReaction).mockRejectedValue(new Error('No se pudo guardar la reacción de prueba'))
  mount()
  fireEvent.click(await screen.findByRole('button',{name:'Me gusta',exact:true}))
  await waitFor(()=>expect(loadFallbackFeed).toHaveBeenCalledTimes(2))
  expect(screen.getByRole('button',{name:'Me gusta',exact:true})).toHaveAttribute('aria-pressed','false')
  expect(screen.getByRole('alert')).toHaveTextContent('No se pudo guardar la reacción de prueba')
})
