import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { CallInviteDialog } from '../../../features/calls/CallInviteDialog'
import { loadFallbackMessagesInbox, searchFallbackProfiles } from '../../../core/fallbackData'
jest.mock('../../../core/fallbackData', () => ({ loadFallbackMessagesInbox: jest.fn(), searchFallbackProfiles: jest.fn() }))
jest.mock('../../../core/profileMediaFields', () => ({ profileAvatarSource: () => '' }))
jest.mock('../../../components/SafeAvatar', () => ({ SafeAvatar: () => <span/> }))
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { this.open = false }
})
beforeEach(() => {
  jest.clearAllMocks()
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValue({ items: [
    { kind: 'chat', profileId: 'self', title: 'Yo', avatarUrl: '' },
    { kind: 'chat', profileId: 'connected', title: 'Conectado', avatarUrl: '' },
    { kind: 'chat', profileId: 'third', title: 'Tercera persona', avatarUrl: '' },
  ] } as Awaited<ReturnType<typeof loadFallbackMessagesInbox>>)
})
test('excludes participants, debounces invitations and confirms only after acknowledgement', async () => {
  let resolve!: () => void
  const invite = jest.fn(() => new Promise<void>(done => { resolve = done }))
  render(<CallInviteDialog excludedIds={['self', 'connected']} onClose={jest.fn()} onInvite={invite}/>)
  const button = await screen.findByRole('button', { name: 'Invitar a Tercera persona' })
  expect(screen.queryByRole('button', { name: 'Invitar a Yo' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Invitar a Conectado' })).not.toBeInTheDocument()
  fireEvent.click(button); fireEvent.click(button)
  expect(invite).toHaveBeenCalledTimes(1); expect(button).toBeDisabled()
  expect(screen.queryByText(/Invitación enviada a/)).not.toBeInTheDocument()
  await act(async () => resolve())
  expect(screen.getByText(/Invitación enviada a Tercera persona/)).toBeInTheDocument()
})
test('failed acknowledgement retains the contact and allows retry', async () => {
  const invite = jest.fn().mockRejectedValueOnce(new Error('Sin conexión. Reintenta.')).mockResolvedValueOnce(undefined)
  render(<CallInviteDialog excludedIds={['self', 'connected']} onClose={jest.fn()} onInvite={invite}/>)
  const button = await screen.findByRole('button', { name: 'Invitar a Tercera persona' })
  fireEvent.click(button); await screen.findByText('Sin conexión. Reintenta.')
  expect(button).toBeEnabled(); fireEvent.click(button)
  await screen.findByText(/Invitación enviada a/); expect(invite).toHaveBeenCalledTimes(2)
})
test('the invitation countdown expires and enables reinviting', async () => {
  jest.useFakeTimers()
  try {
    const invite = jest.fn(async () => new Date(Date.now() + 45000).toISOString())
    render(<CallInviteDialog excludedIds={['self', 'connected']} onClose={jest.fn()} onInvite={invite}/>)
    await act(async () => { jest.advanceTimersByTime(1) })
    const button = screen.getByRole('button', { name: 'Invitar a Tercera persona' })
    await act(async () => { fireEvent.click(button) })
    expect(button).toBeDisabled()
    await act(async () => { jest.advanceTimersByTime(46000) })
    expect(button).toBeEnabled(); expect(button).toHaveTextContent('Reinvitar')
    await act(async () => { fireEvent.click(button) }); expect(invite).toHaveBeenCalledTimes(2)
  } finally { jest.useRealTimers() }
})
test('late search results cannot replace the current query and Escape closes the dialog', async () => {
  let oldResult!: (value: unknown) => void
  jest.mocked(searchFallbackProfiles).mockImplementationOnce(() => new Promise(done => { oldResult = done as typeof oldResult }))
    .mockResolvedValueOnce({ items: [{ id: 'current', display_name: 'Actual' }] } as Awaited<ReturnType<typeof searchFallbackProfiles>>)
  const close = jest.fn()
  render(<CallInviteDialog excludedIds={['self']} onClose={close} onInvite={jest.fn()}/>)
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Anterior' } })
  await waitFor(() => expect(searchFallbackProfiles).toHaveBeenCalledTimes(1))
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Actual' } })
  await screen.findByRole('button', { name: 'Invitar a Actual' })
  await act(async () => oldResult({ items: [{ id: 'old', display_name: 'Anterior' }] }))
  expect(screen.queryByRole('button', { name: 'Invitar a Anterior' })).not.toBeInTheDocument()
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { bubbles: true, cancelable: true }))
  expect(close).toHaveBeenCalledTimes(1)
})
