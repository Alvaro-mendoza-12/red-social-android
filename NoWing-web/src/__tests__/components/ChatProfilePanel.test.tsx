import {render,screen,waitFor,fireEvent} from '@testing-library/react'
import {MemoryRouter} from 'react-router-dom'
import {ChatProfilePanel} from '../../components/ChatProfilePanel'
import {loadFallbackPublicProfile} from '../../core/fallbackData'
jest.mock('../../components/MediaViewer',()=>({MediaViewer:()=> <div role="dialog">Foto ampliada</div>}))
jest.mock('../../core/auth/AuthProvider',()=>({useAuth:()=>({user:{id:'owner'}})}))
jest.mock('../../core/profileVisits',()=>({recordWebProfileVisit:jest.fn(async()=>{})}))
jest.mock('../../core/fallbackData',()=>({loadFallbackPublicProfile:jest.fn()}))
jest.mock('../../core/supabase',()=>({resolveSupabasePublicUrl:(bucket:string,path:string)=>path?`https://media.test/${bucket}/${path}`:''}))
jest.mock('../../components/StreamingMedia',()=>({StreamingImage:(props:object)=><img {...props}/>,StreamingVideo:(props:object)=><video {...props}/>}))
const load=loadFallbackPublicProfile as jest.Mock
test('the contact panel resolves relative avatar and banner fields just like the full profile',async()=>{
 load.mockResolvedValue({profile:{display_name:'Contacto',avatar_url:'user/avatar.webp',banner_url:'user/cover.webp'},stats:{followers:4,following:7}})
 const {container}=render(<MemoryRouter><ChatProfilePanel profileId="friend" onClose={()=>{}}/></MemoryRouter>)
 await screen.findByRole('heading',{name:'Contacto'})
 expect(screen.getByRole('img',{name:'Contacto'})).toHaveAttribute('src','https://media.test/avatars/user/avatar.webp')
 expect(container.querySelector('.chat-profile-cover')).toHaveAttribute('src','https://media.test/covers/user/cover.webp')
})
test('profiles without a cover do not render a broken empty image',async()=>{
 load.mockResolvedValue({profile:{display_name:'Sin portada'},stats:{}})
 const {container}=render(<MemoryRouter><ChatProfilePanel profileId="friend" onClose={()=>{}}/></MemoryRouter>)
 await waitFor(()=>expect(screen.getByRole('heading',{name:'Sin portada'})).toBeInTheDocument())
 expect(container.querySelector('img')).toBeNull()
 expect(container.querySelector('.chat-profile-hero')).toHaveClass('no-cover')
})

test('contact avatar and cover can be expanded without leaving the conversation',async()=>{
 load.mockResolvedValue({profile:{display_name:'Contacto',avatar_url:'user/avatar.webp',cover_url:'user/cover.webp'},stats:{}})
 render(<MemoryRouter><ChatProfilePanel profileId="friend" onClose={()=>{}}/></MemoryRouter>)
 fireEvent.click(await screen.findByRole('button',{name:'Ampliar foto de Contacto'}));expect(screen.getByText('Foto ampliada')).toBeInTheDocument()
 expect(screen.getByRole('button',{name:'Ampliar portada de Contacto'})).toBeInTheDocument()
})
