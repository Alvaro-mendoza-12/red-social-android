import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MediaViewer } from '../../components/MediaViewer'
import { invalidateStorageReadIdentity } from '../../core/media/storageReadSession'
beforeAll(()=>{global.ResizeObserver=class { observe(){} unobserve(){} disconnect(){} } as unknown as typeof ResizeObserver})
jest.mock('../../components/DocumentPreview', () => ({ DocumentPreview: ({ title, pdf }: { title:string; pdf:boolean }) => <section aria-label="Documento PDF" data-pdf={String(pdf)}>{title}</section> }))

jest.mock('../../components/StreamingMedia', () => ({
  StreamingImage: ({onSourceUnavailable,...props}: React.ImgHTMLAttributes<HTMLImageElement> & {onSourceUnavailable?:()=>void}) => {void onSourceUnavailable;return <img {...props}/>},
  StreamingVideo: ({onSourceUnavailable,...props}: React.VideoHTMLAttributes<HTMLVideoElement> & {onSourceUnavailable?:()=>void}) => {void onSourceUnavailable;return <video {...props}/>},
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

test('prepending older history keeps the selected image and its zoom instead of switching files', () => {
  const current = { id: 'current', url: 'https://media.test/current.png', type: 'image' as const, title: 'Actual' }
  const { rerender } = render(<MediaViewer items={[current]} onClose={() => {}} />)
  fireEvent.click(screen.getByRole('button', { name: 'Ampliar', exact: true }))
  const image = screen.getByAltText('Actual')
  rerender(<MediaViewer items={[{ id: 'old', url: 'https://media.test/old.png', type: 'image', title: 'Anterior' }, current]} initialIndex={1} onClose={() => {}} />)
  expect(screen.getByAltText('Actual')).toBe(image)
  expect(image.style.transform).toContain('scale(1.5)')
  expect(screen.getByRole('button', { name: 'Ver archivo 2' })).toHaveAttribute('aria-current', 'true')
})

test('opening a long gallery prioritizes the selected image instead of downloading every original', () => {
  const items = Array.from({ length: 40 }, (_, index) => ({ url: `https://media.test/${index}.jpg`, type: 'image' as const, title: `Foto ${index}` }))
  const { container } = render(<MediaViewer items={items} initialIndex={20} onClose={() => {}} />)
  expect(screen.getByAltText('Foto 20')).toHaveAttribute('src', items[20].url)
  expect(container.querySelectorAll('.media-viewer-filmstrip img')).toHaveLength(7)
  expect(screen.getByRole('button', { name: 'Ver archivo 1' }).querySelector('img')).toBeNull()
  expect(screen.getByText('Cargando imagen…')).toBeInTheDocument()
  fireEvent.load(screen.getByAltText('Foto 20'))
  expect(screen.queryByText('Cargando imagen…')).toBeNull()
})

test('a stalled image offers retry and resets its loading indicator', () => {
  jest.useFakeTimers()
  try {
    render(<MediaViewer items={[{ url: 'https://media.test/slow.jpg', type: 'image', title: 'Lenta' }]} onClose={() => {}} />)
    const first = screen.getByAltText('Lenta')
    act(() => { jest.advanceTimersByTime(15000) })
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar imagen' }))
    expect(screen.getByAltText('Lenta')).not.toBe(first)
    expect(screen.getByText('Cargando imagen…')).toBeInTheDocument()
  } finally { jest.useRealTimers() }
})

test('the selected thumbnail is centered only after the dialog is open', async () => {
  const previous = HTMLElement.prototype.scrollIntoView
  const opened: boolean[] = []
  HTMLElement.prototype.scrollIntoView = function () { opened.push(Boolean(this.closest('dialog')?.open)) }
  try {
    render(<MediaViewer items={[{ url: 'https://media.test/first.jpg', type: 'image' }, { url: 'https://media.test/selected.jpg', type: 'image' }]} initialIndex={1} onClose={() => {}} />)
    await waitFor(() => expect(opened).toEqual([true]))
  } finally { HTMLElement.prototype.scrollIntoView = previous }
})

test('scrolling the filmstrip still loads distant thumbnails without selecting them', () => {
  const previous = globalThis.IntersectionObserver
  let observe!: IntersectionObserverCallback
  globalThis.IntersectionObserver = class {
    constructor(callback: IntersectionObserverCallback) { observe = callback }
    observe() {} disconnect() {}
  } as unknown as typeof IntersectionObserver
  try {
    render(<MediaViewer items={Array.from({ length: 40 }, (_, index) => ({ url: `https://media.test/${index}.jpg`, type: 'image' as const }))} initialIndex={20} onClose={() => {}} />)
    const distant = screen.getByRole('button', { name: 'Ver archivo 1' })
    expect(distant.querySelector('img')).toBeNull()
    act(() => observe([{ target: distant, isIntersecting: true } as unknown as IntersectionObserverEntry], {} as IntersectionObserver))
    expect(distant.querySelector('img')).toHaveAttribute('src', 'https://media.test/0.jpg')
  } finally { globalThis.IntersectionObserver = previous }
})
