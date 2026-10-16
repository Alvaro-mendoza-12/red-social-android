import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MessageSticker } from '../../components/MessageSticker'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
jest.mock('../../components/publicationDialog.css', () => ({}))
jest.mock('../../components/messageSticker.css', () => ({}))
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({user:{id:'owner'},isFallbackSession:true}) }))
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../core/ubuntuBackend', () => ({shouldUseUbuntuBackend:()=>true}))
jest.mock('../../core/runtimeBackend', () => ({rewriteRuntimeStorageUrl:(url:string)=>url}))
jest.mock('../../core/supabase', () => ({supabase:null}))
jest.mock('../../components/ChatAttachmentMedia', () => ({ ChatAttachmentMedia: ({title}:{title:string}) => <img alt={title}/> }))
jest.mock('../../components/MediaDownloadButton', () => ({MediaDownloadButton:()=> <button>Descargar</button>}))
const api=jest.mocked(fallbackApiJson)
const show=HTMLDialogElement.prototype.showModal,close=HTMLDialogElement.prototype.close
beforeAll(()=>{HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','')};HTMLDialogElement.prototype.close=function(){this.removeAttribute('open')}})
afterAll(()=>{HTMLDialogElement.prototype.showModal=show;HTMLDialogElement.prototype.close=close})
beforeEach(()=>{
  Object.defineProperty(crypto,'subtle',{configurable:true,value:{digest:jest.fn().mockResolvedValue(new Uint8Array([1,2]).buffer)}})
  api.mockReset();api.mockResolvedValue([{id:'catalog-id',title:'Hola',media_url:'https://media.test/sticker.gif',is_animated:true,is_favorite:false}])
})
function mount(){const reply=jest.fn(),forward=jest.fn();render(<MessageSticker url="https://media.test/sticker.gif" title="Hola" onReply={reply} onForward={forward}/>);fireEvent.click(screen.getByRole('button',{name:'Opciones del sticker Hola'}));return{reply,forward}}
test('clicking a received sticker offers favorites and reuses its catalog identity',async()=>{
  mount();const save=await screen.findByRole('button',{name:'Añadir a favoritos'});await waitFor(()=>expect(save).toBeEnabled())
  api.mockResolvedValue({ok:true,is_favorite:true});fireEvent.click(save)
  await screen.findByRole('button',{name:'Quitar de favoritos'})
  expect(JSON.parse(String(api.mock.calls[1][1]?.body))).toMatchObject({p_sticker_id:'catalog-id',p_favorite:true,p_is_animated:true})
})
test('reply closes the modal and returns to the same message',async()=>{
  const {reply}=mount();fireEvent.click(await screen.findByRole('button',{name:'Responder'}))
  expect(reply).toHaveBeenCalledTimes(1);expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})
test('failure keeps a retryable favorite action in the modal',async()=>{
  mount();const save=await screen.findByRole('button',{name:'Añadir a favoritos'});await waitFor(()=>expect(save).toBeEnabled())
  api.mockRejectedValue(new Error('network'));await act(async()=>fireEvent.click(save))
  expect(screen.getByRole('alert')).toHaveTextContent('No se pudo guardar el cambio')
  expect(save).toBeEnabled()
})
