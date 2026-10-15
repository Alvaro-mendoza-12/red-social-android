import { act, fireEvent, render, screen } from '@testing-library/react'
import { MediaViewer } from '../../components/MediaViewer'
import { invalidateStorageReadIdentity } from '../../core/media/storageReadSession'
jest.mock('../../components/DocumentPreview', () => ({ DocumentPreview: ({ title, pdf }: { title:string; pdf:boolean }) => <section aria-label="Documento PDF" data-pdf={String(pdf)}>{title}</section> }))

jest.mock('../../components/StreamingMedia', () => ({
  StreamingImage: (props: React.ImgHTMLAttributes<HTMLImageElement>) => <img {...props}/>,
  StreamingVideo: (props: React.VideoHTMLAttributes<HTMLVideoElement>) => <video {...props}/>,
}))

test('shows selected image, zooms and moves between gallery items', () => {
  render(<MediaViewer items={[{url:'https://media.test/one.png',type:'image',title:'Una'},{url:'https://media.test/two.png',type:'image',title:'Dos'}]} onClose={()=>{}}/>)
  expect(screen.getByAltText('Una')).toHaveAttribute('src','https://media.test/one.png')
  fireEvent.click(screen.getByRole('button',{name:'Ampliar',exact:true}))
  expect(screen.getByAltText('Una')).toHaveStyle({width:'150%'})
  fireEvent.click(screen.getByRole('button',{name:'Siguiente archivo',exact:true}))
  expect(screen.getByAltText('Dos')).toHaveAttribute('src','https://media.test/two.png')
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
  expect(screen.getByRole('link',{name:'Descargar archivo'})).toHaveAttribute('href','blob:authorized-pdf')
  expect(screen.queryByRole('button',{name:'Ampliar',exact:true})).toBeNull()
})
