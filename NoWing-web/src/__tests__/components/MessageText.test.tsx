import {render,screen} from '@testing-library/react'
import {MessageText} from '../../components/MessageText'
test('URLs are clickable and have a separate domain card without loading external previews',()=>{
 const {container}=render(<MessageText text="Mira https://example.org/video/uno y https://example.com/"/>);expect(screen.getAllByRole('link')).toHaveLength(3)
 expect(screen.getByText('example.org')).toBeInTheDocument();expect(container.querySelector('iframe,img,video')).toBeNull()
 screen.getAllByRole('link').forEach(a=>expect(a).toHaveAttribute('rel','noopener noreferrer'))
})
test('malformed escapes, markup, unsafe schemes and credentials stay inert',()=>{
 const {container}=render(<MessageText text={'<script>x</script> javascript:alert(1) https://example.org/%ZZ https://user:password@example.org/'}/>);expect(container.querySelector('script')).toBeNull();expect(container.innerHTML).not.toContain('href="javascript:');expect(container.innerHTML).not.toContain('href="https://user:password')
})
