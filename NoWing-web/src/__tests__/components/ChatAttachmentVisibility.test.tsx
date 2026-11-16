import {act,render,screen,waitFor} from '@testing-library/react'
import {ChatAttachmentMedia} from '../../components/ChatAttachmentMedia'
import {readBoundedStorageBlob} from '../../core/media/boundedStorageRead'
import {invalidateStorageReadIdentity,updateStorageReadIdentity} from '../../core/media/storageReadSession'

jest.mock('../../core/runtimeBackend',()=>({rewriteRuntimeStorageUrl:(url:string)=>url,runtimeStoragePreviewUrl:(url:string)=>url+'?width=160'}))
jest.mock('../../core/media/storageReadClient',()=>({prepareStorageReadAccessToken:async()=> 'fixture.test.signature',trustedStorageBackend:()=> 'https://backend.test'}))
jest.mock('../../core/media/boundedStorageRead',()=>({readBoundedStorageBlob:jest.fn(),STORAGE_PREVIEW_MAX_BYTES:16*1024*1024,STORAGE_SHORT_MEDIA_MAX_BYTES:32*1024*1024}))
jest.mock('../../core/media/downloadMedia',()=>({downloadMedia:jest.fn()}))
jest.mock('../../components/MediaViewer',()=>({MediaViewer:()=>null}))
jest.mock('../../components/MediaDownloadButton',()=>({MediaDownloadButton:()=>null}))
jest.mock('../../components/StreamingMedia',()=>({StreamingVideo:()=>null,StreamingAudio:()=>null}))

const read=readBoundedStorageBlob as jest.MockedFunction<typeof readBoundedStorageBlob>
const callbacks:IntersectionObserverCallback[]=[],finish:Array<(blob:Blob)=>void>=[]
let original:typeof IntersectionObserver
function visibility(index:number,visible:boolean){callbacks[index]([{isIntersecting:visible} as IntersectionObserverEntry],{} as IntersectionObserver)}
function tile(animated:boolean){return <ChatAttachmentMedia url="https://backend.test/storage/v1/object/public/stickers/fixture.png" kind="image" title="Sticker visible" tilePreview previewWidth={160} preserveAnimation={animated} imageLink={false} inlineDownload={false}/>}

beforeEach(()=>{
 original=globalThis.IntersectionObserver;callbacks.length=0;finish.length=0;read.mockReset()
 Object.defineProperty(globalThis,'IntersectionObserver',{configurable:true,value:class{
   constructor(callback:IntersectionObserverCallback){callbacks.push(callback)}
   observe(){}disconnect(){}unobserve(){}takeRecords(){return[]}
 }})
 Object.assign(URL,{createObjectURL:jest.fn(()=> 'blob:fixture-visible'),revokeObjectURL:jest.fn()})
 updateStorageReadIdentity('fixture-visibility','fallback')
 read.mockImplementation(()=>new Promise(resolve=>finish.push(resolve)))
})
afterEach(()=>{act(()=>invalidateStorageReadIdentity());Object.defineProperty(globalThis,'IntersectionObserver',{configurable:true,value:original})})

test('a disconnected observer cannot cancel the new visible thumbnail and leave it loading',async()=>{
 const view=render(tile(false));act(()=>visibility(0,true));await waitFor(()=>expect(read).toHaveBeenCalledTimes(1))
 view.rerender(tile(true));await waitFor(()=>expect(read).toHaveBeenCalledTimes(2))
 act(()=>visibility(1,true));const signal=read.mock.calls[1][0].signal
 act(()=>visibility(0,false))
 expect(signal.aborted).toBe(false)
 await act(async()=>finish[1](new Blob(['fixture-image'],{type:'image/png'})))
 expect(await screen.findByAltText('Sticker visible')).toBeInTheDocument()
 expect(screen.queryByTitle('Cargando sticker')).toBeNull()
})

test('a late visible callback cannot start a read after the current observer marked the tile offscreen',async()=>{
 const view=render(tile(false));view.rerender(tile(true))
 await act(async()=>{visibility(1,false);visibility(0,true);await Promise.resolve()})
 expect(read).not.toHaveBeenCalled()
})

test('the current observer still releases a read when its tile leaves the viewport',async()=>{
 const view=render(tile(false));view.rerender(tile(true));act(()=>visibility(1,true))
 await waitFor(()=>expect(read).toHaveBeenCalledTimes(1));const signal=read.mock.calls[0][0].signal
 act(()=>visibility(1,false));expect(signal.aborted).toBe(true)
})
