import {useState} from 'react'
import {fireEvent,render,screen,waitFor,within} from '@testing-library/react'
import {PromotionEditor} from '../../components/PromotionEditor'
import type {PromotionCatalog} from '../../components/PromotionCarousel'

const mockApi=jest.fn(),mockUpload=jest.fn(),mockChanged=jest.fn(),mockClose=jest.fn()
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:(...args:unknown[])=>mockApi(...args)}))
jest.mock('../../core/fallbackData',()=>({uploadFallbackFile:(...args:unknown[])=>mockUpload(...args)}))
jest.mock('../../core/promotionCatalogEvents',()=>({notifyPromotionCatalogChanged:(...args:unknown[])=>mockChanged(...args)}))
jest.mock('../../components/StreamingMedia',()=>({StreamingImage:()=> <span>Imagen del anuncio</span>}))
jest.mock('../../components/PromotionCarousel',()=>({PromotionCarousel:()=> <span>Vista previa del anuncio</span>}))
const catalog:PromotionCatalog={canManage:true,items:[{id:'ad-one',title:'Pruebas',description:'Descripción',imageUrl:'',destination:'/app/messages',cta:'Abrir mensajes',active:true,cards:[{title:'Tarjeta de prueba',description:'',imageUrl:'https://owned.invalid/image.png',destination:'/app/messages',cta:'Abrir mensajes'}]}]}
function Manager(){const [value,setValue]=useState(catalog);return <PromotionEditor catalog={value} accountId="owner" onRefresh={setValue} onClose={mockClose}/>}

beforeEach(()=>{
 Object.defineProperty(crypto,'randomUUID',{configurable:true,value:()=> 'synthetic-'+Math.random()})
 mockApi.mockReset();mockApi.mockImplementation(async(_path:string,init?:RequestInit)=>init?{ok:true}:catalog)
 mockUpload.mockReset();mockUpload.mockResolvedValue({publicUrl:'https://owned.invalid/new.png'})
 mockChanged.mockReset();mockClose.mockReset()
 HTMLDialogElement.prototype.showModal=jest.fn(function(this:HTMLDialogElement){this.setAttribute('open','')})
 HTMLDialogElement.prototype.close=jest.fn()
 URL.createObjectURL=jest.fn(()=> 'blob:preview');URL.revokeObjectURL=jest.fn()
})

test('canceling deletion keeps the announcement and never sends a delete',async()=>{
 render(<Manager/>);fireEvent.click(screen.getByRole('button',{name:'Eliminar',exact:true}))
 expect(screen.getByRole('alertdialog')).toHaveTextContent('Pruebas')
 fireEvent.click(screen.getByRole('button',{name:'Cancelar'}))
 expect(screen.getByRole('button',{name:'Editar'})).toBeEnabled()
 expect(mockApi.mock.calls.some(([,init])=>init?.method==='DELETE')).toBe(false)
})

test('confirmed deletion removes only the selected announcement and keeps the manager open',async()=>{
 render(<Manager/>);await waitFor(()=>expect(mockApi).toHaveBeenCalled())
 fireEvent.click(screen.getByRole('button',{name:'Eliminar',exact:true}))
 fireEvent.click(screen.getByRole('button',{name:'Eliminar anuncio',exact:true}))
 await waitFor(()=>expect(screen.getByRole('status')).toHaveTextContent('Anuncio eliminado.'))
 expect(mockApi).toHaveBeenCalledWith('/v1/data/social/promotions/ad-one',expect.objectContaining({method:'DELETE',expectedAccountId:'owner'}))
 expect(screen.queryByRole('button',{name:'Editar'})).not.toBeInTheDocument()
 expect(mockChanged).toHaveBeenCalledWith('owner');expect(mockClose).not.toHaveBeenCalled()
})

test('a failed deletion retains the announcement and allows cancellation or closing',async()=>{
 mockApi.mockImplementation(async(_path:string,init?:RequestInit)=>{if(init?.method==='DELETE')throw Error('offline');return catalog})
 render(<Manager/>);fireEvent.click(screen.getByRole('button',{name:'Eliminar',exact:true}))
 fireEvent.click(screen.getByRole('button',{name:'Eliminar anuncio',exact:true}))
 await waitFor(()=>expect(screen.getByRole('alert')).toHaveTextContent('No se pudo eliminar'))
 fireEvent.click(screen.getByRole('button',{name:'Cancelar'}))
 expect(screen.getByRole('button',{name:'Editar'})).toBeEnabled()
 fireEvent.click(screen.getByRole('button',{name:'Cerrar publicidad'}));expect(mockClose).toHaveBeenCalledTimes(1)
})

test('editing can advance through preview, publish and return without closing the app',async()=>{
 render(<Manager/>);fireEvent.click(screen.getByRole('button',{name:'Editar'}))
 fireEvent.change(screen.getByLabelText(/Título del anuncio/),{target:{value:'Título actualizado'}})
 fireEvent.click(screen.getByRole('button',{name:'Continuar'}));fireEvent.click(screen.getByRole('button',{name:'Continuar'}))
 expect(screen.getByText('Vista previa del anuncio')).toBeInTheDocument()
 fireEvent.click(screen.getByRole('button',{name:'Publicar anuncio'}))
 await waitFor(()=>expect(screen.getByRole('status')).toHaveTextContent('Anuncio publicado.'))
 expect(mockApi).toHaveBeenCalledWith('/v1/data/social/promotions/ad-one',expect.objectContaining({method:'PATCH'}))
 expect(mockClose).not.toHaveBeenCalled()
})

test('creating accepts an image, publishes the carousel and can close from the manager',async()=>{
 render(<Manager/>);fireEvent.click(screen.getByRole('button',{name:'Crear anuncio'}))
 fireEvent.change(screen.getByLabelText(/Título del anuncio/),{target:{value:'Nuevo anuncio'}})
 fireEvent.click(screen.getByRole('button',{name:'Continuar'}))
 fireEvent.change(screen.getByLabelText('Título de tarjeta'),{target:{value:'Un producto'}})
 fireEvent.change(screen.getByLabelText('Imagen de tarjeta 1'),{target:{files:[new File(['png'],'image.png',{type:'image/png'})]}})
 fireEvent.click(screen.getByRole('button',{name:'Continuar'}))
 fireEvent.click(screen.getByRole('button',{name:'Publicar anuncio'}))
 await waitFor(()=>expect(mockApi).toHaveBeenCalledWith('/v1/data/social/promotions',expect.objectContaining({method:'POST'})))
 await waitFor(()=>expect(screen.getByRole('status')).toHaveTextContent('Anuncio publicado.'))
 fireEvent.click(screen.getByRole('button',{name:'Cerrar publicidad'}));expect(mockClose).toHaveBeenCalledTimes(1)
})

test('pause and activation stay in the manager',async()=>{
 render(<Manager/>);fireEvent.click(screen.getByRole('button',{name:'Pausar'}))
 await waitFor(()=>expect(mockApi).toHaveBeenCalledWith('/v1/data/social/promotions/ad-one',expect.objectContaining({method:'PATCH',body:JSON.stringify({active:false})})))
 expect(within(screen.getByRole('dialog')).getByText('Mis anuncios')).toBeInTheDocument()
 expect(mockClose).not.toHaveBeenCalled()
})
