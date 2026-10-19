import { commentAudioUrl } from '../../core/commentMedia'
jest.mock('../../core/supabase',()=>({resolveSupabasePublicUrl:(bucket:string,value:string)=>value.startsWith('https:')?value:`https://media.example/${bucket}/${value}`}))
test('retains Android audio and legacy URLs without treating text or scripts as media',()=>{
 expect(commentAudioUrl({content:'[Audio]',media_url:'https://media.example/one.m4a',media_type:'audio'})).toBe('https://media.example/one.m4a')
 expect(commentAudioUrl({content:'[Audio] https://media.example/old.m4a'})).toBe('https://media.example/old.m4a')
 expect(commentAudioUrl({content_type:'audio',media_url:'user/one.m4a'})).toContain('/post-comments-media/user/one.m4a')
 expect(commentAudioUrl({content:'hello',media_url:'photo.png'})).toBe('')
 expect(commentAudioUrl({content:'[Audio]',media_url:'javascript:alert(1)'})).toBe('')
})
