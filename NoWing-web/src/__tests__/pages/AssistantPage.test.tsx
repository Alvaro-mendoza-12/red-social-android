import { randomUUID } from 'node:crypto'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { AssistantPage } from '../../features/assistant/AssistantPage'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
jest.mock('../../features/assistant/assistant.css',()=>({}))
jest.mock('../../core/auth/AuthProvider',()=>({useAuth:()=>({user:{id:'owner'}})}))
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:jest.fn()}))
const api=jest.mocked(fallbackApiJson)
beforeEach(()=>{localStorage.clear();Object.defineProperty(crypto,'randomUUID',{configurable:true,value:randomUUID});api.mockReset();api.mockResolvedValue({answer:'Una respuesta útil'});HTMLElement.prototype.scrollIntoView=jest.fn()})
function send(text='Pregunta'){fireEvent.change(screen.getByRole('textbox'),{target:{value:text}});fireEvent.click(screen.getByRole('button',{name:'Enviar al asistente'}))}
test('a conversation survives a reload under the same account',async()=>{
  const first=render(<AssistantPage/>);send();await screen.findByText('Una respuesta útil');first.unmount()
  render(<AssistantPage/>);expect(screen.getByText('Pregunta')).toBeInTheDocument();expect(screen.getByText('Una respuesta útil')).toBeInTheDocument()
})
test('retrying a failed answer does not duplicate the submitted question',async()=>{
  api.mockRejectedValueOnce(new Error('network'));render(<AssistantPage/>);send()
  fireEvent.click(await screen.findByRole('button',{name:'Volver a intentar'}));await screen.findByText('Una respuesta útil')
  expect(screen.getAllByText('Pregunta')).toHaveLength(1);expect(api).toHaveBeenCalledTimes(2)
})
test('stop cancels the request and discards a late answer',async()=>{
  let resolve!:(value:unknown)=>void
  api.mockImplementationOnce(()=>new Promise(done=>{resolve=done}));render(<AssistantPage/>);send()
  await screen.findByRole('button',{name:'Detener respuesta'});fireEvent.click(screen.getByRole('button',{name:'Detener respuesta'}))
  await act(async()=>resolve({answer:'Respuesta tardía'}));expect(screen.queryByText('Respuesta tardía')).not.toBeInTheDocument()
  await waitFor(()=>expect(screen.getByRole('button',{name:'Enviar al asistente'})).toBeDisabled())
})
