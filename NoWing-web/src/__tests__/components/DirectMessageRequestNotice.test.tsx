import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { DirectMessageRequestNotice } from '../../components/DirectMessageRequestNotice'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:jest.fn()}))
const api=jest.mocked(fallbackApiJson)
beforeEach(()=>api.mockReset())
test('recipient accepts a single introduction with account guarded requests',async()=>{
 api.mockResolvedValueOnce({status:'pending_incoming'}).mockResolvedValueOnce({status:'open'})
 render(<DirectMessageRequestNotice chatId="chat" accountId="account" revision={1}/> )
 fireEvent.click(await screen.findByRole('button',{name:'Aceptar'}))
 await waitFor(()=>expect(screen.queryByLabelText('Solicitud de mensaje')).toBeNull())
 expect(api).toHaveBeenLastCalledWith('/v1/data/messages/chats/chat/request',expect.objectContaining({method:'POST',expectedAccountId:'account',body:JSON.stringify({action:'accept'})}))
})
test('outgoing introduction explains the limit and cannot accept itself',async()=>{
 api.mockResolvedValueOnce({status:'pending_outgoing'})
 render(<DirectMessageRequestNotice chatId="chat" accountId="account" revision={1}/> )
 expect(await screen.findByText(/Solicitud enviada/)).toBeTruthy()
 expect(screen.queryByRole('button',{name:'Aceptar'})).toBeNull()
})
