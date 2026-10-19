import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { PublicComments } from '../../components/PublicComments'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
jest.mock('../../core/auth/AuthProvider',()=>({useAuth:()=>({user:{id:'me'},isFallbackSession:true})}))
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:jest.fn(),isFallbackAuthConfigured:true}))
jest.mock('../../core/supabase',()=>({supabase:null,resolveSupabasePublicUrl:(_bucket:string,value:string)=>value}))
jest.mock('../../core/ubuntuRealtime',()=>({subscribeUbuntuRealtime:()=>()=>{}}))
jest.mock('../../components/ChatAttachmentMedia',()=>({ChatAttachmentMedia:()=>null}))
jest.mock('../../components/CommentAudio',()=>({CommentAudio:()=>null}))
const api=fallbackApiJson as jest.Mock
beforeEach(()=>api.mockReset())
test('replies are collapsed under the original, and reply targets and reactions reach the protected API',async()=>{
 const items=[{id:'root',user_id:'friend',user_name:'Amiga',content:'Pregunta'},{id:'child',parent_comment_id:'root',user_id:'me',user_name:'Yo',content:'Respuesta'}]
 api.mockImplementation((_path:string,options?:{method?:string})=>Promise.resolve(options?.method?{ok:true}:{items}))
 render(<PublicComments kind="video" targetId="video"/>)
 await screen.findByText('Pregunta');expect(screen.queryByText('Respuesta')).toBeNull()
 fireEvent.click(screen.getByRole('button',{name:'Ver 1 respuesta'}));expect(screen.getByText('Respuesta')).toBeVisible()
 fireEvent.click(screen.getAllByRole('button',{name:'Responder'})[0]);fireEvent.change(screen.getByRole('textbox',{name:'Escribir comentario'}),{target:{value:'Otra respuesta'}})
 fireEvent.click(screen.getByRole('button',{name:'Enviar comentario'}))
 await waitFor(()=>expect(api).toHaveBeenCalledWith('/v1/data/videos/video/comments',expect.objectContaining({method:'POST',expectedAccountId:'me',body:expect.stringContaining('"parentId":"root"')})))
 await waitFor(()=>expect(screen.getByRole('button',{name:'Enviar comentario'})).toBeDisabled())
 fireEvent.click(screen.getByRole('button',{name:'Reaccionar LOVE al comentario de Amiga'}))
 await waitFor(()=>expect(api).toHaveBeenCalledWith('/v1/data/videos/comments/root/reaction',expect.objectContaining({method:'PUT',body:'{"reactionType":"LOVE"}'})))
})
test('post comments retain a failed draft instead of losing the user input',async()=>{
 api.mockImplementation((_path:string,options?:{method?:string})=>options?.method?Promise.reject(new Error('NETWORK_ERROR')):Promise.resolve({comments:[]}))
 render(<PublicComments kind="post" targetId="post"/>)
 await screen.findByText('Sé la primera persona en comentar.')
 fireEvent.change(screen.getByRole('textbox',{name:'Escribir comentario'}),{target:{value:'No perder este texto'}});fireEvent.click(screen.getByRole('button',{name:'Enviar comentario'}))
 await screen.findAllByRole('alert');expect(screen.getByRole('textbox',{name:'Escribir comentario'})).toHaveValue('No perder este texto')
 expect(api).toHaveBeenCalledWith('/v1/data/feed/posts/post/comments',expect.objectContaining({expectedAccountId:'me'}))
})
