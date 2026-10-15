import { fireEvent, render, screen } from '@testing-library/react'
import { SafeAvatar } from '../../components/SafeAvatar'

describe('SafeAvatar', () => {
  it('renders an image for a photo avatar', () => {
    render(<SafeAvatar url="https://cdn.example/avatar.webp" name="Alvaro" />)

    expect(screen.getByRole('img', { name: 'Alvaro' })).toHaveAttribute(
      'src',
      'https://cdn.example/avatar.webp',
    )
  })

  it('renders a muted looping video when the media type declares a video avatar', () => {
    const { container } = render(
      <SafeAvatar
        url="https://cdn.example/avatar.bin"
        mediaType="video/webm"
        name="Adolf"
      />,
    )
    const video = container.querySelector('video')

    expect(video).toHaveAttribute('src', 'https://cdn.example/avatar.bin')
    expect(video).toHaveAttribute('aria-label', 'Adolf')
    expect(video).toHaveAttribute('loop')
    expect(video).toHaveAttribute('playsinline')
  })

  it('detects a video avatar from an mp4 URL and falls back accessibly on error', () => {
    const { container } = render(<SafeAvatar url="https://cdn.example/avatar.mp4?v=4" name="Mendoza" />)
    const video = container.querySelector('video')
    expect(video).not.toBeNull()

    fireEvent.error(video as HTMLVideoElement)
    expect(screen.getByLabelText('Mendoza')).toHaveTextContent('M')
  })
})
