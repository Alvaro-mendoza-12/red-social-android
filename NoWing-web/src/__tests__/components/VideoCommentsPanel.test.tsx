import {fireEvent,render,screen,waitFor} from '@testing-library/react'
import {VideoCommentsPanel} from '../../components/VideoCommentsPanel'
import {fallbackApiJson} from '../../core/auth/fallbackAuth'
jest.mock('../../core/auth/AuthProvider',()=>({useAuth:()=>({user:{id:'canonical-profile'},isFallbackSession:true})}))
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:jest.fn(),isFallbackAuthConfigured:true}))
jest.mock('../../core/supabase',()=>({supabase:null}))
jest.mock('../../core/ubuntuRealtime',()=>({subscribeUbuntuRealtime:()=>()=>{}}))
jest.mock('../../components/ChatAttachmentMedia',()=>({ChatAttachmentMedia:()=>null}))
const request=fallbackApiJson as jest.Mock

test('closing or replacing the panel focuses the feed without scrolling to the old video',async()=>{
 request.mockResolvedValue({items:[]})
 const {rerender}=render(<section><div className="reels-vertical-feed" tabIndex={0}><button>Comentarios anteriores</button></div></section>)
 const old=screen.getByRole('button',{name:'Comentarios anteriores'});old.focus()
 rerender(<section><div className="reels-vertical-feed" tabIndex={0}><button>Comentarios anteriores</button></div><VideoCommentsPanel videoId="next-video" onClose={()=>{}}/></section>)
 await screen.findByRole('dialog')
 const feed=old.parentElement!,focus=jest.spyOn(feed,'focus')
 rerender(<section><div className="reels-vertical-feed" tabIndex={0}><button>Comentarios anteriores</button></div></section>)
 expect(focus).toHaveBeenCalledWith({preventScroll:true});expect(document.activeElement).toBe(feed)
 focus.mockRestore()
})
test('loads and paginates comments using the canonical signed-in account',async()=>{
 request.mockResolvedValue({items:Array.from({length:42},(_,i)=>({id:'c'+i,user_name:'Persona',content:'Comentario '+i}))})
 const {container}=render(<VideoCommentsPanel videoId="video-id" onClose={()=>{}}/>)
 await waitFor(()=>expect(container.querySelectorAll('.video-comment')).toHaveLength(30))
 expect(request).toHaveBeenCalledWith('/v1/data/videos/video-id/comments?limit=500',{expectedAccountId:'canonical-profile'})
 fireEvent.click(screen.getByRole('button',{name:'Ver comentarios anteriores'}));expect(container.querySelectorAll('.video-comment')).toHaveLength(42)
})
