import {fireEvent,render,screen} from '@testing-library/react'
import {MessageText} from '../../components/MessageText'
import {MemoryRouter} from 'react-router-dom'

test('long messages expand and collapse while keeping full link targets',()=>{
 const text='Texto largo '.repeat(90)+'https://example.org/full-target'
 const {container,rerender}=render(<MessageText text={text}/>)
 expect(container.querySelector('p')).toHaveClass('message-text-collapsed')
 fireEvent.click(screen.getByRole('button',{name:'Leer más'}))
 expect(container.querySelector('p')).not.toHaveClass('message-text-collapsed')
 expect(screen.getByRole('button',{name:'Ver menos'})).toHaveAttribute('aria-expanded','true')
 expect(screen.getByRole('link',{name:'https://example.org/full-target'})).toHaveAttribute('href','https://example.org/full-target')
 fireEvent.click(screen.getByRole('button',{name:'Ver menos'}))
 rerender(<MessageText text="Corto"/>);expect(screen.queryByRole('button')).toBeNull()
})

test('only unambiguous room handles become profile links and URLs remain separate',()=>{
 render(<MemoryRouter><MessageText text="Hola @ana, @otro y https://example.org/@ana" mentions={[{id:'person',username:'ana',name:'Ana'}]}/></MemoryRouter>)
 expect(screen.getByRole('link',{name:'@ana'})).toHaveAttribute('href','/app/profile/person')
 expect(screen.queryByRole('link',{name:'@otro'})).not.toBeInTheDocument()
})
test('URLs are clickable and have a separate domain card without loading external previews',()=>{
 const {container}=render(<MessageText text="Mira https://example.org/video/uno y https://example.com/"/>);expect(screen.getAllByRole('link')).toHaveLength(3)
 expect(screen.getByText('example.org')).toBeInTheDocument();expect(container.querySelector('iframe,img,video')).toBeNull()
 screen.getAllByRole('link').forEach(a=>expect(a).toHaveAttribute('rel','noopener noreferrer'))
})
test('malformed escapes, markup, unsafe schemes and credentials stay inert',()=>{
 const {container}=render(<MessageText text={'<script>x</script> javascript:alert(1) https://example.org/%ZZ https://user:password@example.org/'}/>);expect(container.querySelector('script')).toBeNull();expect(container.innerHTML).not.toContain('href="javascript:');expect(container.innerHTML).not.toContain('href="https://user:password')
})
