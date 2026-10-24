import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ProfileHeader } from '../../ui/novvin/ProfileHeader'

jest.mock('../../components/ProfileMediaButton', () => ({ ProfileMediaButton: ({ children }: { children: React.ReactNode }) => children }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => <img alt="Avatar"/> }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: ({ alt }: { alt: string }) => <img alt={alt}/> }))

test('a custom cover no longer hides the selected profile animation and color', () => {
  const { container } = render(<MemoryRouter><ProfileHeader name="Prueba" username="prueba" avatarUrl="" coverUrl="https://media.test/cover.jpg" profileEffect="constellation" profileTheme="oceano" identity={{ verified: false, publicId: '', admin: false }} followers={0} following={0}/></MemoryRouter>)
  expect(screen.getByAltText('Portada de Prueba')).toBeInTheDocument()
  expect(container.querySelector('.profile-cover-effect-overlay')).toHaveAttribute('data-effect', 'constellation')
  expect(container.querySelector('.novvin-profile-main-header')).toHaveStyle('--profile-accent: #69cfff')
})
