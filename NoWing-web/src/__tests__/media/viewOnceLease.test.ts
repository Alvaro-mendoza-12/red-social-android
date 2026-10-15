import { validateViewOnceLease, viewOnceLimit } from '../../core/media/viewOnceLease'
import { getStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'
const backend='https://backend.test',now=Date.parse('2026-10-08T20:00:00Z')
const row=()=>({allowed:true,already_consumed:false,media_url:`${backend}/storage/v1/object/public/chat-media/a/file.png?lease=${'a'.repeat(43)}`,lease_expires_at:new Date(now+300000).toISOString(),media_size_bytes:2,content_type:'view_once_image'})
beforeEach(()=>updateStorageReadIdentity('account-a','fallback'))
const parse=(value:unknown)=>validateViewOnceLease(value,backend,getStorageReadIdentity(),'image',now)
test('accepts an object or PostgREST array with an expiring current lease',()=>{
 expect(parse(row()).bytes).toBe(2);expect(parse([row()]).kind).toBe('image');expect(Object.isFrozen(parse(row()))).toBe(true)
})
test.each([null,[],{}, {allowed:true}, {...row(),allowed:'true'}, {...row(),already_consumed:true}])('rejects legacy, malformed or consumed response %s',(value)=>expect(()=>parse(value)).toThrow())
test.each(['https://foreign.test/storage/v1/object/public/chat-media/a/f.png?lease='+ 'a'.repeat(43),'javascript:alert(1)',backend+'/storage/v1/object/public/chat-media/a/f.png',row().media_url+'&lease='+ 'b'.repeat(43),row().media_url+'&token=x',row().media_url+'#x'])('rejects untrusted or replay-shaped URL %s',url=>expect(()=>parse({...row(),media_url:url})).toThrow())
test.each([now-1,now,now+360001])('rejects stale or excessive expiry %s',expires=>expect(()=>parse({...row(),lease_expires_at:new Date(expires).toISOString()})).toThrow())
test.each([0,-1,1.5,viewOnceLimit('image')+1,NaN])('rejects media size %s',bytes=>expect(()=>parse({...row(),media_size_bytes:bytes})).toThrow())
test('rejects content type mismatch and caps videos at 32 MiB',()=>{
 expect(()=>parse({...row(),content_type:'view_once_video'})).toThrow()
 expect(viewOnceLimit('video')).toBe(32*1024*1024)
})
