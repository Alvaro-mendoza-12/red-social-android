import { act, fireEvent, render, screen } from '@testing-library/react'
import { MediaViewer } from '../../components/MediaViewer'
import { invalidateStorageReadIdentity } from '../../core/media/storageReadSession'
beforeAll(()=>{global.ResizeObserver=class { observe(){} unobserve(){} disconnect(){} } as unknown as typeof ResizeObserver})
jest.mock('../../components/DocumentPreview', () => ({ DocumentPreview: ({ title, pdf }: { title:string; pdf:boolean }) => <section aria-label="Documento PDF" data-pdf={String(pdf)}>{title}</section> }))

jest.mock('../../components/StreamingMedia', () => ({
  StreamingImage: (props: React.ImgHTMLAttributes<HTMLImageElement>) => <img {...props}/>,
  StreamingVideo: (props: React.VideoHTMLAttributes<HTMLVideoElement>) => <video {...props}/>,
}))

test('shows selected image, zooms and moves between gallery items', () => {
  render(<MediaViewer items={[{url:'https://media.test/one.png',type:'image',title:'Una'},{url:'https://media.test/two.png',type:'image',title:'Dos'}]} onClose={()=>{}}/>)
  expect(screen.getByAltText('Una')).toHaveAttribute('src','https://media.test/one.png')
  fireEvent.click(screen.getByRole('button',{name:'Ampliar',exact:true}))
  expect(screen.getByAltText('Una')).toHaveStyle({transform:'translate(0px, 0px) scale(1.5)'})
  fireEvent.click(screen.getByRole('button',{name:'Siguiente archivo',exact:true}))
  expect(screen.getByAltText('Dos')).toHaveAttribute('src','https://media.test/two.png')
  expect(screen.getByAltText('Dos')).toHaveStyle({transform:'translate(0px, 0px) scale(1)'})
  fireEvent.click(screen.getByRole('button',{name:'Archivo anterior',exact:true}))
  expect(screen.getByAltText('Una')).toBeInTheDocument()
})

test('revokes displayed sources immediately when the account is invalidated', () => {
  const close=jest.fn()
  render(<MediaViewer items={[{url:'blob:private',type:'image',title:'Privada'}]} onClose={close}/>)
  act(()=>invalidateStorageReadIdentity())
  expect(screen.getByAltText('Privada')).not.toHaveAttribute('src')
  expect(close).toHaveBeenCalledTimes(1)
})

test('PDF uses the in-app renderer and exposes its authorized download', () => {
  render(<MediaViewer items={[{url:'blob:authorized-pdf',type:'pdf',title:'Documento',downloadUrl:'blob:authorized-pdf'}]} onClose={()=>{}}/>)
  expect(screen.getByRole('region',{name:'Documento PDF'})).toHaveAttribute('data-pdf','true')
  expect(document.querySelector('iframe')).toBeNull()
  expect(screen.getByRole('button',{name:'Descargar archivo'})).toBeEnabled()
  expect(screen.queryByRole('button',{name:'Ampliar',exact:true})).toBeNull()
})

test('the filmstrip selects images and videos directly and downloads are always available',()=>{
 const {container}=render(<MediaViewer items={[{url:'https://media.test/one.png',type:'image'},{url:'https://media.test/two.mp4',type:'video'}]} onClose={()=>{}}/>)
 expect(screen.getByRole('navigation',{name:'Archivos de la conversación'})).toBeInTheDocument()
 fireEvent.click(screen.getByRole('button',{name:'Ver archivo 2'}))
 expect(screen.getByRole('button',{name:'Ver archivo 2'})).toHaveAttribute('aria-current','true')
 expect(container.querySelector('.media-viewer-stage video')).toHaveAttribute('controls')
 expect(screen.getByRole('button',{name:'Descargar archivo'})).toBeEnabled()
})

test('wheel, double click and keyboard control image zoom within bounds',()=>{
 render(<MediaViewer items={[{url:'https://media.test/zoom.png',type:'image',title:'Zoom'}]} onClose={()=>{}}/> )
 const stage=screen.getByRole('img',{name:/Usa la rueda/})
 fireEvent.wheel(stage,{deltaY:-400});expect(screen.getByAltText('Zoom').style.transform).not.toContain('scale(1)')
 fireEvent.click(screen.getByRole('button',{name:'Restablecer tamaño'}));expect(screen.getByAltText('Zoom').style.transform).toContain('scale(1)')
 fireEvent.doubleClick(stage,{clientX:50,clientY:50});expect(screen.getByAltText('Zoom').style.transform).toContain('scale(2.5)')
 fireEvent.keyDown(stage,{key:'0'});expect(screen.getByAltText('Zoom').style.transform).toContain('scale(1)')
 fireEvent.click(screen.getByRole('button',{name:'Ampliar',exact:true}));fireEvent.keyDown(stage,{key:'+'});expect(screen.getByAltText('Zoom').style.transform).toContain('scale(2)')
})

test('Escape closes only the viewer, preserving its parent contact panel',()=>{
 const parent=jest.fn(),close=jest.fn()
 render(<section onKeyDown={parent}><MediaViewer items={[{url:'https://media.test/photo.png',type:'image'}]} onClose={close}/></section>)
 fireEvent.keyDown(screen.getByRole('dialog',{name:'Visor multimedia'}),{key:'Escape'})
 expect(close).toHaveBeenCalledTimes(1);expect(parent).not.toHaveBeenCalled()
})
