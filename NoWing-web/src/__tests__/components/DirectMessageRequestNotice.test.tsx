import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { DirectMessageRequestNotice } from '../../components/DirectMessageRequestNotice'
import { directMessageWriteRestriction, useDirectMessageRequest } from '../../components/useDirectMessageRequest'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:jest.fn()}))
const api=jest.mocked(fallbackApiJson)

function Harness({ chat = 'chat' }: { chat?: string }) {
  const request = useDirectMessageRequest(chat, 'account')
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

test('an earlier visit cannot accept the conversation after the user leaves and returns', async () => {
  let completeDecision: (value: { status: 'open' }) => void = () => { throw new Error('No decision pending') }
  api.mockResolvedValueOnce({ status: 'pending_incoming' })
    .mockImplementationOnce(() => new Promise((resolve) => { completeDecision = resolve }))
    .mockResolvedValueOnce({ status: 'open' })
    .mockResolvedValueOnce({ status: 'pending_incoming' })

  const view = render(<Harness />)
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  view.rerender(<Harness chat="other" />)
  await waitFor(() => expect(api).toHaveBeenCalledWith('/v1/data/messages/chats/other/request', expect.anything()))
  view.rerender(<Harness />)

  const current = await screen.findByRole('button', { name: 'Aceptar' })
  expect((current as HTMLButtonElement).disabled).toBe(false)
  await act(async () => { completeDecision({ status: 'open' }) })
  expect(screen.getByRole('button', { name: 'Aceptar' })).toBeTruthy()
})
