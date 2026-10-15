import { act, render, screen, waitFor } from '@testing-library/react'
import { ViewOnceMediaDialog } from '../../components/ViewOnceMediaDialog'
import { readBoundedStorageBlob } from '../../core/media/boundedStorageRead'
import { getStorageReadIdentity, invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'
import type { ViewOnceLease } from '../../core/media/viewOnceLease'
jest.mock('../../core/media/storageReadClient',()=>({prepareStorageReadAccessToken:jest.fn(async()=> 'synthetic.test.signature')}))
jest.mock('../../core/media/boundedStorageRead',()=>({readBoundedStorageBlob:jest.fn(),STORAGE_PREVIEW_MAX_BYTES:16*1024*1024,STORAGE_SHORT_MEDIA_MAX_BYTES:32*1024*1024}))
const read=readBoundedStorageBlob as jest.MockedFunction<typeof readBoundedStorageBlob>
const created=jest.fn(()=> 'blob:owned-view-once'),revoked=jest.fn(),close=jest.fn()
function lease(kind:'image'|'video'='image'):ViewOnceLease{return {url:`https://backend.test/storage/v1/object/public/chat-media/a/file?lease=${'a'.repeat(43)}`,backend:'https://backend.test',kind,bytes:2,identity:getStorageReadIdentity(),expiresAt:Date.now()+300000}}
beforeEach(()=>{
 read.mockReset();created.mockClear();revoked.mockClear();close.mockClear();Object.assign(URL,{createObjectURL:created,revokeObjectURL:revoked})
 Object.defineProperty(document,'visibilityState',{value:'visible',configurable:true})
 Object.assign(HTMLDialogElement.prototype,{showModal(){this.open=true},close(){this.open=false}})
 updateStorageReadIdentity('account-a','fallback')
})
afterEach(()=>act(()=>invalidateStorageReadIdentity()))
test('performs one complete authorized GET and releases Blob at close',async()=>{
 read.mockResolvedValue(new Blob([new Uint8Array([1,2])],{type:'image/png'}))
 const {unmount,container}=render(<ViewOnceMediaDialog lease={lease()} onClose={close}/>)
 expect((await screen.findByAltText('Foto de una sola visualización')).getAttribute('src')).toBe('blob:owned-view-once')
 expect(read).toHaveBeenCalledTimes(1);expect(container.querySelector('a')).toBeNull()
 unmount();expect(revoked).toHaveBeenCalledWith('blob:owned-view-once')
})
test('denial shows no public fallback or retry',async()=>{
 read.mockRejectedValue(new Error('MEDIA_READ_404'))
 const {container}=render(<ViewOnceMediaDialog lease={lease()} onClose={close}/>)
 await screen.findByRole('alert');expect(container.querySelector('img,video,a')).toBeNull();expect(created).not.toHaveBeenCalled();expect(screen.queryByRole('button',{name:'Reintentar'})).toBeNull()
})
test.each(['text/html','image/svg+xml','application/octet-stream'])('never displays unexpected content %s',async type=>{
 read.mockResolvedValue(new Blob([new Uint8Array([1,2])],{type}))
 const {container}=render(<ViewOnceMediaDialog lease={lease()} onClose={close}/>);await screen.findByRole('alert')
 expect(container.querySelector('img,video,a')).toBeNull();expect(created).not.toHaveBeenCalled()
})
test('rejects truncated body before creating Blob URL',async()=>{
 read.mockResolvedValue(new Blob([new Uint8Array([1])],{type:'image/png'}))
 render(<ViewOnceMediaDialog lease={lease()} onClose={close}/>);await screen.findByRole('alert');expect(created).not.toHaveBeenCalled()
})
test('background closes the viewer and clears decoded image synchronously',async()=>{
 read.mockResolvedValue(new Blob([new Uint8Array([1,2])],{type:'image/png'}))
 const {container}=render(<ViewOnceMediaDialog lease={lease()} onClose={close}/>);await screen.findByAltText('Foto de una sola visualización')
 Object.defineProperty(document,'visibilityState',{value:'hidden',configurable:true});act(()=>document.dispatchEvent(new Event('visibilitychange')))
 expect(close).toHaveBeenCalledTimes(1);expect(revoked).toHaveBeenCalledWith('blob:owned-view-once');expect(container.querySelector('img')?.hasAttribute('src')).toBe(false)
})
test('account change aborts reading and discards a late result',async()=>{
 let complete!:(blob:Blob)=>void;read.mockImplementation(()=>new Promise(resolve=>{complete=resolve}))
 render(<ViewOnceMediaDialog lease={lease()} onClose={close}/>);await waitFor(()=>expect(read).toHaveBeenCalledTimes(1))
 act(()=>updateStorageReadIdentity('account-b','fallback'))
 await act(async()=>complete(new Blob([new Uint8Array([1,2])],{type:'image/png'})))
 expect(close).toHaveBeenCalledTimes(1);expect(created).not.toHaveBeenCalled();expect(read.mock.calls[0][0].signal.aborted).toBe(true)
})
