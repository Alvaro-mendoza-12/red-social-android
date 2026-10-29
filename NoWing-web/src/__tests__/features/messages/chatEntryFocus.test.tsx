import { act, render, screen, waitFor } from '@testing-library/react'
import { useRef } from 'react'
import { useChatEntryFocus } from '../../../features/messages/useChatEntryFocus'

function Entry({ request = 0, ready = true }: { request?: number; ready?: boolean }) {
  const input = useRef<HTMLInputElement>(null)
  useChatEntryFocus(input, 'account/chat/room', ready, request)
  return <><button>Buscar</button><input ref={input} aria-label="Mensaje"/></>
}

test('reopening the same embedded conversation focuses it while refreshes leave other inputs alone', async () => {
  const view = render(<Entry/>)
  const input = screen.getByRole('textbox', { name: 'Mensaje' })
  await waitFor(() => expect(input).toHaveFocus())
  act(() => screen.getByRole('button', { name: 'Buscar' }).focus())
  view.rerender(<Entry/>)
  expect(input).not.toHaveFocus()
  view.rerender(<Entry request={1}/>)
  await waitFor(() => expect(input).toHaveFocus())
})
