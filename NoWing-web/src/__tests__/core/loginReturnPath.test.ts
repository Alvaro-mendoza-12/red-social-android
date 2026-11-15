import { loginReturnPath, publicSharedContentPath } from '../../core/auth/loginReturnPath'

describe('loginReturnPath', () => {
  it('preserves a shared video and its fragment when sign-in is required', () => {
    const redirect = loginReturnPath('/app/reels/abc', '?resumeMs=5000', '#comments')
    const url = new URL(redirect, 'https://red-social-android.vercel.app')

    expect(url.pathname).toBe('/login')
    expect(url.searchParams.get('next')).toBe('/app/reels/abc?resumeMs=5000#comments')
  })
})

test('public share routes preserve the exact destination for later sign-in', () => {
  const id = '00000000-0000-4000-8000-000000000002'
  const video = `/app/reels/${id}`
  const guestPath = publicSharedContentPath(video, '?resumeMs=5000', '#comments')
  expect(guestPath).toBe(`/explore/videos/${id}?next=${encodeURIComponent(video + '?resumeMs=5000#comments')}`)
  expect(publicSharedContentPath(`/app/feed/post/${id}`)).toBe(`/explore/posts/${id}?next=${encodeURIComponent('/app/feed/post/' + id)}`)
  expect(publicSharedContentPath(`/app/messages/${id}`)).toBeNull()
  expect(publicSharedContentPath('/app/reels/invalid')).toBeNull()
})
