import {act,fireEvent,render,screen} from '@testing-library/react'
import {ProfilePostPreview} from '../../components/ProfilePostPreview'
jest.mock('../../core/runtimeBackend',()=>({runtimeStoragePreviewUrl:(url:string)=>url}))
jest.mock('../../components/StreamingMedia',()=>({StreamingImage:(props:object)=><img {...props}/>,StreamingVideo:(props:object)=><video {...props}/>}))
beforeEach(()=>{jest.useFakeTimers();window.matchMedia=jest.fn().mockReturnValue({matches:true})})
afterEach(()=>jest.useRealTimers())
test('hover waits before loading the preview and leaves the normal link usable',()=>{
 render(<ProfilePostPreview media={{url:'https://media.test/photo.png',mediaType:'image'}} text="Publicación"><a href="/app/feed/post/1">Abrir publicación</a></ProfilePostPreview>)
 fireEvent.mouseEnter(screen.getByRole('article'));expect(screen.queryByRole('tooltip')).toBeNull();act(()=>jest.advanceTimersByTime(250));expect(screen.getByRole('tooltip')).toBeInTheDocument();expect(screen.getByRole('link')).toHaveAttribute('href','/app/feed/post/1');fireEvent.mouseLeave(screen.getByRole('article'));expect(screen.queryByRole('tooltip')).toBeNull()
})
test('keyboard focus previews, Escape closes and video previews stay muted',()=>{
 render(<ProfilePostPreview media={{url:'https://media.test/clip.mp4',mediaType:'video'}} text="Video"><a href="/app/feed/post/1">Abrir publicación</a></ProfilePostPreview>)
 fireEvent.focus(screen.getByRole('link'));act(()=>jest.advanceTimersByTime(250));expect(screen.getByRole('tooltip').querySelector('video')?.muted).toBe(true);fireEvent.keyDown(screen.getByRole('link'),{key:'Escape'});expect(screen.queryByRole('tooltip')).toBeNull()
})
test('text posts do not create an empty preview',()=>{
 render(<ProfilePostPreview text="Texto"><a href="/app/feed/post/1">Abrir publicación</a></ProfilePostPreview>);fireEvent.mouseEnter(screen.getByRole('article'));act(()=>jest.advanceTimersByTime(1000));expect(screen.queryByRole('tooltip')).toBeNull()
})

test('resizing the viewport closes a floating preview',()=>{render(<ProfilePostPreview media={{url:'https://media.test/photo.png',mediaType:'image'}} text="Foto"><a href="/app/feed/post/1">Abrir publicación</a></ProfilePostPreview>);fireEvent.focus(screen.getByRole('link'));act(()=>jest.advanceTimersByTime(250));expect(screen.getByRole('tooltip')).toBeInTheDocument();fireEvent(window,new Event('resize'));expect(screen.queryByRole('tooltip')).toBeNull()})
