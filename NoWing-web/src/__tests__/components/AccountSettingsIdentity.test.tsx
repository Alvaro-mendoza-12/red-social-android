import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AccountSettingsPage } from '../../features/profile/AccountSettingsPage'
import { updateFallbackSessionProfile } from '../../core/auth/fallbackAuth'
import { loadFallbackProfileMe, updateFallbackProfileMe } from '../../core/fallbackData'

let mockIsFallbackSession = true
let mockUser: {id:string,email:string,user_metadata:Record<string, unknown>} | null
jest.mock('../../core/auth/AuthProvider',()=>({useAuth:()=>({user:mockUser,isFallbackSession:mockIsFallbackSession})}))
jest.mock('../../core/auth/fallbackAuth',()=>({changeFallbackPassword:jest.fn(),updateFallbackSessionProfile:jest.fn()}))
jest.mock('../../core/fallbackData',()=>({loadFallbackProfileMe:jest.fn(),updateFallbackProfileMe:jest.fn(),uploadFallbackFile:jest.fn()}))
jest.mock('../../core/supabase',()=>({supabase:null}))
jest.mock('../../components/StreamingMedia',()=>({StreamingImage:()=>null}))
jest.mock('../../components/SafeAvatar',()=>({SafeAvatar:()=>null}))
jest.mock('../../components/QrCodeTile',()=>({QrCodeTile:()=>null}))
const user=(id:string)=>({id,email:`${id}@example.invalid`,user_metadata:{}})
const page=()=> <MemoryRouter><AccountSettingsPage /></MemoryRouter>
function deferred() {
  let resolve!: (value: {profile:Record<string,unknown>})=>void
  const promise=new Promise<{profile:Record<string,unknown>}>(yes=>{resolve=yes})
  return {promise,resolve}
}
beforeEach(()=>{
  jest.clearAllMocks(); mockUser=user('first'); mockIsFallbackSession=true
  jest.mocked(loadFallbackProfileMe).mockImplementation(async()=>({profile:{display_name:mockUser?.id},posts:[],reports:[],stats:{posts:0,videos:0,chats:0,followers:0,following:0,reportsSent:0}}))
})

test('switching accounts clears passwords and unsaved profile drafts',async()=>{
  const view=render(page())
  await screen.findByDisplayValue('first')
  fireEvent.change(screen.getByLabelText('Nombre visible'),{target:{value:'private draft'}})
  for(const label of ['Contraseña actual','Nueva contraseña','Confirmar nueva contraseña']) fireEvent.change(screen.getByLabelText(label),{target:{value:'synthetic-only'}})
  mockUser=user('second');view.rerender(page())
  await screen.findByDisplayValue('second')
  for(const label of ['Contraseña actual','Nueva contraseña','Confirmar nueva contraseña']) expect(screen.getByLabelText(label)).toHaveValue('')
  expect(screen.queryByDisplayValue('private draft')).not.toBeInTheDocument()
})

test('a late profile read cannot overwrite the next account',async()=>{
  const first=deferred()
  jest.mocked(loadFallbackProfileMe).mockReturnValueOnce(first.promise as ReturnType<typeof loadFallbackProfileMe>)
  const view=render(page())
  mockUser=user('second');view.rerender(page())
  await screen.findByDisplayValue('second')
  await act(async()=>first.resolve({profile:{display_name:'old private profile'}}))
  expect(screen.getByLabelText('Nombre visible')).toHaveValue('second')
})

test('a late profile save cannot populate a new account or show its success message',async()=>{
  const saving=deferred()
  jest.mocked(updateFallbackProfileMe).mockReturnValue(saving.promise)
  const view=render(page())
  await screen.findByDisplayValue('first')
  fireEvent.click(screen.getByRole('button',{name:'Guardar personalización'}))
  mockUser=user('second');view.rerender(page())
  await screen.findByDisplayValue('second')
  await act(async()=>saving.resolve({profile:{display_name:'saved old account'}}))
  expect(screen.getByLabelText('Nombre visible')).toHaveValue('second')
  expect(screen.queryByText('Perfil actualizado.')).not.toBeInTheDocument()
  expect(updateFallbackSessionProfile).not.toHaveBeenCalled()
})


test('signing out removes the form and returning to the same account starts clean',async()=>{
  const view=render(page())
  await screen.findByDisplayValue('first')
  fireEvent.change(screen.getByLabelText('Nueva contraseña'),{target:{value:'synthetic-draft'}})
  mockUser=null;view.rerender(page())
  expect(screen.queryByLabelText('Nueva contraseña')).not.toBeInTheDocument()
  expect(screen.getByText('Inicia sesión para ver tus ajustes')).toBeInTheDocument()
  mockUser=user('first');view.rerender(page())
  await screen.findByDisplayValue('first')
  expect(screen.getByLabelText('Nueva contraseña')).toHaveValue('')
})


test('changing backend with the same account ID resets password drafts',async()=>{
  const view=render(page())
  await screen.findByDisplayValue('first')
  fireEvent.change(screen.getByLabelText('Nueva contraseña'),{target:{value:'synthetic-draft'}})
  mockIsFallbackSession=false;view.rerender(page())
  expect(screen.getByText('Supabase no configurado')).toBeInTheDocument()
  mockIsFallbackSession=true;view.rerender(page())
  await screen.findByDisplayValue('first')
  expect(screen.getByLabelText('Nueva contraseña')).toHaveValue('')
})

test('a save from a previous login cannot update the new session of the same account',async()=>{
  const saving=deferred()
  jest.mocked(updateFallbackProfileMe).mockReturnValue(saving.promise)
  const view=render(page())
  await screen.findByDisplayValue('first')
  fireEvent.click(screen.getByRole('button',{name:'Guardar personalización'}))
  mockUser=null;view.rerender(page())
  mockUser=user('first');view.rerender(page())
  await screen.findByDisplayValue('first')
  await act(async()=>saving.resolve({profile:{display_name:'previous-login-save'}}))
  expect(screen.getByLabelText('Nombre visible')).toHaveValue('first')
  expect(updateFallbackSessionProfile).not.toHaveBeenCalled()
})
