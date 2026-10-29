import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { VoiceRoomInvitePage } from '../../../features/voice/VoiceRoomInvitePage'

test('desktop invitation resolves to the authenticated room route with automatic admission', async () => {
  render(<MemoryRouter initialEntries={['/voice/room-123']}><Routes>
    <Route path="/voice/:roomId" element={<VoiceRoomInvitePage />} />
    <Route path="/app/voice/:roomId" element={<p>Room destination</p>} />
  </Routes></MemoryRouter>)
  expect(await screen.findByText('Room destination')).toBeInTheDocument()
})

test('invalid invitation gives an error without navigating or revealing room details', () => {
  render(<MemoryRouter initialEntries={['/voice/a%2Fb']}><Routes>
    <Route path="/voice/:roomId" element={<VoiceRoomInvitePage />} />
  </Routes></MemoryRouter>)
  expect(screen.getByText('Enlace de sala inválido')).toBeInTheDocument()
})
