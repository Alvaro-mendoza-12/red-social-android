import {fireEvent,render,screen,waitFor,within} from '@testing-library/react'
import {CommunityQuestion} from '../../components/CommunityQuestion'
import {fallbackApiJson} from '../../core/auth/fallbackAuth'
jest.mock('../../core/auth/AuthProvider',()=>({useAuth:()=>({user:{id:'me'}})}))
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:jest.fn()}))
jest.mock('../../components/communityQuestion.css',()=>({}))
const api=fallbackApiJson as jest.Mock
const reference={questionId:'00000000-0000-4000-8000-000000000071',prompt:'3 + 2',maxLength:40}
beforeEach(()=>{api.mockReset();api.mockResolvedValue({...reference,id:reference.questionId,canRespond:true,closed:false,total:50,nextOffset:null,answers:Array.from({length:50},(_,i)=>({id:'a'+i,userId:'u'+i,name:'Persona '+i,username:'user'+i,content:'Respuesta '+i,createdAt:new Date().toISOString(),likes:0,dislikes:0,myVote:0}))})})
test('answer form is independent of the scrollable list and submitting switches views',async()=>{
 render(<CommunityQuestion scope="community" roomId="room" content={JSON.stringify(reference)}/>)
 await waitFor(()=>expect(screen.getByRole('button',{name:'Responder',exact:true})).toBeEnabled())
 fireEvent.click(screen.getByRole('button',{name:'Responder',exact:true}))
 expect(screen.getByRole('tab',{name:'Tu respuesta'})).toHaveAttribute('aria-selected','true')
 expect(document.querySelector('.question-answer-list')).toBeNull()
 fireEvent.change(screen.getByLabelText('Tu respuesta'),{target:{value:'5'}})
 fireEvent.click(within(screen.getByRole('dialog')).getByRole('button',{name:'Responder',exact:true}))
 await waitFor(()=>expect(screen.getByRole('tab',{name:'Respuestas (50)'})).toHaveAttribute('aria-selected','true'))
 expect(screen.queryByLabelText('Tu respuesta')).toBeNull()
 expect(document.querySelectorAll('.question-answer-list article')).toHaveLength(50)
 expect(api).toHaveBeenCalledWith(expect.stringContaining('/answers'),expect.objectContaining({method:'POST',body:'{"content":"5"}'}))
})
test('floating answers open the list and switching tabs does not duplicate answers',async()=>{
 render(<CommunityQuestion scope="group" roomId="room" content={JSON.stringify(reference)}/>)
 await waitFor(()=>expect(document.querySelectorAll('.floating-answer')).toHaveLength(3))
 fireEvent.click(document.querySelector('.floating-answer')!)
 expect(screen.getByRole('tab',{name:'Respuestas (50)'})).toHaveAttribute('aria-selected','true')
 fireEvent.click(screen.getByRole('tab',{name:'Tu respuesta'}));expect(screen.getByLabelText('Tu respuesta')).toBeVisible()
 fireEvent.click(screen.getByRole('tab',{name:'Respuestas (50)'}));expect(document.querySelectorAll('.question-answer-list article')).toHaveLength(50)
})
