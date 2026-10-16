import {act,fireEvent,render,screen} from '@testing-library/react'
import {readConversationDraft,writeConversationDraft,useConversationDraft} from '../../core/conversationDrafts'
function Composer({owner='owner',room='room',scope='chat'}:{owner?:string;room?:string;scope?:'chat'|'group'|'community'}) {const [text,setText]=useConversationDraft(owner,scope,room);return <input aria-label="Borrador" value={text} onChange={e=>setText(e.target.value)}/>}
beforeEach(()=>localStorage.clear())
test('draft survives unmount and a new component and preserves line breaks',()=>{
 const a=render(<Composer/>);fireEvent.change(screen.getByLabelText('Borrador'),{target:{value:'hola mañana'}});a.unmount()
 render(<Composer/>);expect(screen.getByLabelText('Borrador')).toHaveValue('hola mañana')
 writeConversationDraft('owner','chat','multiline','hola\nmañana');expect(readConversationDraft('owner','chat','multiline')).toBe('hola\nmañana')
})
test('changing rooms and accounts never carries text into the next composer',()=>{
 const view=render(<Composer/>);fireEvent.change(screen.getByLabelText('Borrador'),{target:{value:'Privado A'}})
 view.rerender(<Composer room="other"/>);expect(screen.getByLabelText('Borrador')).toHaveValue('');fireEvent.change(screen.getByLabelText('Borrador'),{target:{value:'B'}})
 view.rerender(<Composer owner="other-account"/>);expect(screen.getByLabelText('Borrador')).toHaveValue('')
 view.rerender(<Composer/>);expect(screen.getByLabelText('Borrador')).toHaveValue('Privado A')
})
test('kind isolation, deletion and empty identity',()=>{
 act(()=>{writeConversationDraft('owner','chat','room','chat');writeConversationDraft('owner','group','room','grupo');writeConversationDraft('owner','community','room','comunidad')})
 expect(readConversationDraft('owner','group','room')).toBe('grupo');expect(readConversationDraft('owner','community','room')).toBe('comunidad')
 act(()=>writeConversationDraft('owner','chat','room',''));expect(readConversationDraft('owner','chat','room')).toBe('')
 writeConversationDraft(undefined,'chat','room','wrong');expect(readConversationDraft(undefined,'chat','room')).toBe('')
})
test('clearing browser storage does not resurrect an older cached draft',()=>{
 writeConversationDraft('owner','chat','room','anterior');localStorage.clear();expect(readConversationDraft('owner','chat','room')).toBe('')
})
