import { act, render, screen } from '@testing-library/react'
import { ProfileStatusBadges } from '../../components/ProfileStatusBadges'
import { publicProfileSummaries } from '../../core/publicProfileSummaries'
let mockOwner = 'account-a'
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: mockOwner } }) }))
jest.mock('../../core/publicProfileSummaries', () => ({ publicProfileSummaries: jest.fn() }))
const profiles = jest.mocked(publicProfileSummaries)
beforeEach(() => { mockOwner = 'account-a'; profiles.mockReset() })
test('shares an authorized identity lookup and retains the badge when display text changes', async () => {
  profiles.mockResolvedValue(new Map([['profile-a',{id:'profile-a',is_admin:true}]]))
  const { rerender } = render(<><span>Apodo</span><ProfileStatusBadges profileId="profile-a"/><ProfileStatusBadges profileId="profile-a"/></>)
  await screen.findAllByLabelText('Administrador verificado')
  expect(profiles).toHaveBeenCalledTimes(1)
  rerender(<><span>Nombre real</span><ProfileStatusBadges profileId="profile-a"/><ProfileStatusBadges profileId="profile-a"/></>)
  expect(screen.getAllByLabelText('Administrador verificado')).toHaveLength(2)
})
test('does not render a late identity response for the previous account', async () => {
  let finish!: (value: Map<string, Record<string, unknown>>) => void
  profiles.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const { rerender } = render(<ProfileStatusBadges profileId="profile-b"/>)
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 5)) })
  profiles.mockResolvedValue(new Map([['profile-b',{id:'profile-b',is_admin:false}]]))
  mockOwner = 'account-b'; rerender(<ProfileStatusBadges profileId="profile-b"/>)
  await act(async () => { finish(new Map([['profile-b',{id:'profile-b',is_admin:true}]])); await new Promise(resolve => setTimeout(resolve, 5)) })
  expect(screen.queryByLabelText('Administrador verificado')).toBeNull()
})

test('verification and administrator award are both visible for the same public identity', async () => {
  profiles.mockResolvedValue(new Map([['profile-c',{id:'profile-c',is_verified:true,badges:[{id:'admin',is_admin_badge:true}]}]]))
  render(<ProfileStatusBadges profileId="profile-c"/>)
  expect(await screen.findByLabelText('Cuenta verificada')).toBeInTheDocument()
  expect(screen.getByLabelText('Administrador verificado')).toBeInTheDocument()
})

test('reopening a profile uses its current identity while an expired badge lookup refreshes', async () => {
  const id = 'reopened-profile'
  profiles.mockResolvedValueOnce(new Map([[id, { id, is_verified: true }]]))
  const firstVisit = render(<ProfileStatusBadges profileId={id}/>)
  expect(await screen.findByLabelText('Cuenta verificada')).toBeInTheDocument()
  firstVisit.unmount()

  const clock = jest.spyOn(Date, 'now').mockReturnValue(Date.now() + 121_000)
  try {
    profiles.mockResolvedValueOnce(new Map([[id, { id, is_admin: true }]]))
    render(<ProfileStatusBadges profileId={id} initialIdentity={{ verified: false, admin: false, publicId: '' }}/>)
    expect(screen.queryByLabelText('Cuenta verificada')).toBeNull()
    expect(await screen.findByLabelText('Administrador verificado')).toBeInTheDocument()
    expect(screen.queryByLabelText('Cuenta verificada')).toBeNull()
  } finally {
    clock.mockRestore()
  }
})
