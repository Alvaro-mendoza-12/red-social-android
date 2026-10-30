import {act,fireEvent,render,screen} from '@testing-library/react'
import {MediaViewer} from '../../components/MediaViewer'
import {browserStreamingSource} from '../../core/media/browserStreaming'
import {invalidateStorageReadIdentity,updateStorageReadIdentity} from '../../core/media/storageReadSession'
jest.mock('../../core/runtimeBackend',()=>({...jest.requireActual('../../core/runtimeBackend'),rewriteRuntimeStorageUrl:(value:string)=>value}))
jest.mock('../../core/media/storageReadClient',()=>({trustedStorageBackend:(value:string)=>value.startsWith('https://private.test/')?'https://private.test':''}))
jest.mock('../../core/media/browserStreaming',()=>({browserStreamingSource:jest.fn(),cachedBrowserStreamingSource:()=>undefined,subscribeBrowserStreamingRecovery:()=>()=>{}}))
jest.mock('../../core/media/imagePreviewCache',()=>({cachedStorageImagePreview:()=>undefined}))
jest.mock('../../components/DocumentPreview',()=>({DocumentPreview:()=>null}))
jest.mock('../../components/MediaDownloadButton',()=>({MediaDownloadButton:()=> <button>Descargar</button>}))
const stream=jest.mocked(browserStreamingSource)
beforeAll(()=>{global.ResizeObserver=class{observe(){}disconnect(){}} as unknown as typeof ResizeObserver})
test.each(['image','video'] as const)('the %s viewer reports an unavailable source and can load it again',async(type)=>{
 jest.useFakeTimers();updateStorageReadIdentity('viewer-qa','fallback')
 stream.mockRejectedValue(new Error('MEDIA_STREAMING_UNAVAILABLE'))
 const {container,unmount}=render(<MediaViewer items={[{url:'https://private.test/storage/v1/object/public/chat-media/qa.'+(type==='image'?'png':'mp4'),type,title:'Archivo QA'}]} onClose={()=>{}}/>)
 try {
  await act(async()=>{await Promise.resolve()})
  for(const delay of [350,1200,3000])await act(async()=>{jest.advanceTimersByTime(delay);await Promise.resolve()})
  expect(screen.getByText(/No se pudo mostrar este archivo/)).toBeInTheDocument()
  stream.mockResolvedValue('/__novvin_media/recovered-viewer')
  fireEvent.click(screen.getByRole('button',{name:'Volver a cargar'}))
  await act(async()=>{await Promise.resolve()})
  expect(container.querySelector('.media-viewer-stage img,.media-viewer-stage video')?.getAttribute('src')).toContain('/__novvin_media/recovered-viewer')
 }finally{unmount();invalidateStorageReadIdentity();stream.mockReset();jest.useRealTimers()}
})
