import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { DirectMessageRequestNotice, directMessageWriteRestriction, useDirectMessageRequest } from '../../components/DirectMessageRequestNotice'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:jest.fn()}))
const api=jest.mocked(fallbackApiJson)

function Harness() {
  const request = useDirectMessageRequest('chat', 'account')
  return <><DirectMessageRequestNotice request={request}/><p>{directMessageWriteRestriction(request.status)}</p></>
}
beforeEach(()=>api.mockReset())
test('recipient accepts a single introduction with account guarded requests',async()=>{
 api.mockResolvedValueOnce({status:'pending_incoming'}).mockResolvedValueOnce({status:'open'})
 render(<Harness/> )
 fireEvent.click(await screen.findByRole('button',{name:'Aceptar'}))
 await waitFor(()=>expect(screen.queryByLabelText('Solicitud de mensaje')).toBeNull())
 expect(api).toHaveBeenCalledWith('/v1/data/messages/chats/chat/request',expect.objectContaining({method:'POST',expectedAccountId:'account',body:JSON.stringify({action:'accept'})}))
})
test('outgoing introduction explains the limit and cannot accept itself',async()=>{
 api.mockResolvedValueOnce({status:'pending_outgoing'})
 render(<Harness/> )
 expect(await screen.findByText(/Podrás escribir de nuevo/)).toBeTruthy()
 expect(screen.queryByRole('button',{name:'Aceptar'})).toBeNull()
})
