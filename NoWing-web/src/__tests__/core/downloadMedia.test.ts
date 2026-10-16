import {downloadMedia,originalFileName} from '../../core/media/downloadMedia'
import {updateStorageReadIdentity,invalidateStorageReadIdentity} from '../../core/media/storageReadSession'
jest.mock('../../core/media/browserStreaming',()=>({browserStreamingSource:jest.fn(async()=>'/__novvin_media/opaque')}))
jest.mock('../../core/media/storageReadClient',()=>({trustedStorageBackend:()=>''}))
jest.mock('../../core/runtimeBackend',()=>({rewriteRuntimeStorageUrl:(s:string)=>s}))
test('preserves ZIP and media extensions rather than using the opaque handle as a filename',()=>{
 expect(originalFileName('/storage/folder.zip','carpeta.zip')).toBe('carpeta.zip')
 expect(originalFileName('/storage/photo%20one.png','Foto del chat')).toBe('photo one.png')
 expect(originalFileName('/storage/voice.ogg','Audio')).toBe('voice.ogg')
 expect(originalFileName('/storage/clip.mp4','Video')).toBe('clip.mp4')
})

test('an account change during a download discards the bytes before creating a file',async()=>{
 updateStorageReadIdentity('owner-a','fallback')
 const previous=global.fetch,create=jest.fn(),oldCreate=URL.createObjectURL
 URL.createObjectURL=create
 const cancel=jest.fn(async()=>{}),releaseLock=jest.fn()
 global.fetch=jest.fn(async()=>({ok:true,headers:{get:()=>null},body:{getReader:()=>({read:async()=>{updateStorageReadIdentity('owner-b','fallback');return{done:false,value:new Uint8Array([1,2])}},cancel,releaseLock})}})) as jest.Mock
 try{await expect(downloadMedia('https://media.test/private.zip','carpeta.zip')).rejects.toThrow('MEDIA_SESSION_CHANGED');expect(create).not.toHaveBeenCalled();expect(cancel).toHaveBeenCalled()}finally{global.fetch=previous;URL.createObjectURL=oldCreate;invalidateStorageReadIdentity()}
})

test('oversized files are rejected before buffering and unsafe schemes are never fetched',async()=>{
 const previous=global.fetch,cancel=jest.fn(async()=>{})
 global.fetch=jest.fn(async()=>({ok:true,headers:{get:()=>String(129*1024*1024)},body:{cancel}})) as jest.Mock
 try{await expect(downloadMedia('https://media.test/file.zip')).rejects.toThrow('DOWNLOAD_TOO_LARGE');expect(cancel).toHaveBeenCalled();await expect(downloadMedia('javascript:alert(1)')).rejects.toThrow('INVALID_DOWNLOAD');expect(global.fetch).toHaveBeenCalledTimes(1)}finally{global.fetch=previous}
})
