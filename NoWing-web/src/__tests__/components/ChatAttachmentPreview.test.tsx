import {fireEvent,render,screen} from '@testing-library/react'
import {ChatAttachmentPreview,type AttachmentDraft} from '../../components/ChatAttachmentPreview'
jest.mock('../../components/SpellingSuggestions',()=>({SpellingSuggestions:()=>null}))
jest.mock('../../components/StreamingMedia',()=>({StreamingImage:(props:object)=><img {...props}/>,StreamingVideo:(props:object)=><video {...props}/>}))
const make=(id:string):AttachmentDraft=>({id,file:new File(['image'],id+'.png',{type:'image/png'}),contentType:'image',previewUrl:'blob:'+id,caption:''})

test('compact icon controls retain accessible names when their text is hidden',()=>{
 const onEdit=jest.fn(),onToggleSticker=jest.fn()
 const {container}=render(<ChatAttachmentPreview items={[make('compact')]} draft="" busy={false} preparing={false} status="" onCaption={jest.fn()} onRemove={jest.fn()} onClose={jest.fn()} onAdd={jest.fn()} onEdit={onEdit} onToggleSticker={onToggleSticker} onSend={jest.fn()}/>)
 container.querySelectorAll<HTMLElement>('nav button span').forEach(label=>{label.style.display='none'})
 fireEvent.click(screen.getByRole('button',{name:'Editar'}))
 fireEvent.click(screen.getByRole('button',{name:'Enviar como sticker'}))
 expect(onEdit).toHaveBeenCalledWith('compact')
 expect(onToggleSticker).toHaveBeenCalledWith('compact')
 container.querySelectorAll('nav svg').forEach(icon=>expect(icon).toHaveAttribute('aria-hidden','true'))
})
test('preview selection, adding and removing files never sends automatically',()=>{
 const onSend=jest.fn(event=>event.preventDefault()),onRemove=jest.fn(),onEdit=jest.fn(),onToggleSticker=jest.fn(),onClose=jest.fn()
 const props={items:[make('first')],draft:'',busy:false,preparing:false,status:'',onCaption:jest.fn(),onRemove,onClose,onAdd:jest.fn(),onEdit,onToggleSticker,onSend}
 const {rerender}=render(<ChatAttachmentPreview {...props}/>)
 expect(onSend).not.toHaveBeenCalled()
 rerender(<ChatAttachmentPreview {...props} items={[make('first'),make('second')]}/>)
 expect(screen.getByRole('button',{name:'Seleccionar second.png'})).toHaveAttribute('aria-pressed','true')
 fireEvent.click(screen.getByRole('button',{name:'Quitar first.png'}));expect(onRemove).toHaveBeenCalledWith('first')
 fireEvent.click(screen.getByRole('button',{name:'Editar'}));expect(onEdit).toHaveBeenCalledWith('second')
 fireEvent.click(screen.getByRole('button',{name:'Enviar como sticker'}));expect(onToggleSticker).toHaveBeenCalledWith('second')
 expect(onSend).not.toHaveBeenCalled()
 fireEvent.click(screen.getByRole('button',{name:'Enviar 2 adjuntos'}));expect(onSend).toHaveBeenCalledTimes(1)
 fireEvent.click(screen.getByRole('button',{name:'Cancelar adjuntos'}));expect(onClose).toHaveBeenCalledTimes(1)
})
test('documents have a readable preview and retain their own captions',()=>{
 const item={...make('document'),file:new File(['pdf'],'Informe.pdf',{type:'application/pdf'}),contentType:'file' as const,caption:'Para revisar'}
 const onCaption=jest.fn()
 render(<ChatAttachmentPreview items={[item]} draft="" busy={false} preparing={false} status="" onCaption={onCaption} onRemove={jest.fn()} onClose={jest.fn()} onAdd={jest.fn()} onEdit={jest.fn()} onToggleSticker={jest.fn()} onSend={jest.fn()}/>)
 expect(screen.getByRole('textbox',{name:'Mensaje del adjunto'})).toHaveValue('Para revisar')
 expect(screen.queryByRole('button',{name:'Enviar como sticker'})).toBeNull()
 fireEvent.change(screen.getByRole('textbox'),{target:{value:'Otro texto'}});expect(onCaption).toHaveBeenCalledWith('document','Otro texto')
})
test('Enter cannot submit while files are still preparing',()=>{
 const onSend=jest.fn(),props={items:[make('first')],draft:'',busy:false,preparing:true,status:'Preparando…',onCaption:jest.fn(),onRemove:jest.fn(),onClose:jest.fn(),onAdd:jest.fn(),onEdit:jest.fn(),onToggleSticker:jest.fn(),onSend}
 const {container}=render(<ChatAttachmentPreview {...props}/>)
 fireEvent.submit(container.querySelector('form')!)
 expect(onSend).not.toHaveBeenCalled()
 expect(screen.getByRole('button',{name:'Enviar 1 adjuntos'})).toBeDisabled()
})

test('original animated files stay untouched until the user explicitly sends',()=>{
 const original=new File(['GIF89a-original-animation'],'sticker.gif',{type:'image/gif'})
 const item={...make('animation'),file:original,contentType:'sticker' as const,previewUrl:'blob:original-gif'}
 const onSend=jest.fn(event=>event.preventDefault())
 render(<ChatAttachmentPreview items={[item]} draft="" busy={false} preparing={false} status="" onCaption={jest.fn()} onRemove={jest.fn()} onClose={jest.fn()} onAdd={jest.fn()} onEdit={jest.fn()} onToggleSticker={jest.fn()} onSend={onSend}/>)
 expect(screen.getByRole('img',{name:'sticker.gif'})).toHaveAttribute('src','blob:original-gif')
 expect(item.file).toBe(original)
 expect(screen.queryByText(/Si el sticker se ve fijo/)).toBeNull()
 expect(onSend).not.toHaveBeenCalled()
 fireEvent.click(screen.getByRole('button',{name:'Enviar 1 adjuntos'}))
 expect(onSend).toHaveBeenCalledTimes(1)
 expect(item.file).toBe(original)
})
