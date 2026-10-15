import { loginReturnPath } from '../../core/auth/loginReturnPath'

describe('loginReturnPath', () => {
  it('preserves a shared video and its fragment when sign-in is required', () => {
    const redirect = loginReturnPath('/app/reels/abc', '?resumeMs=5000', '#comments')
    const url = new URL(redirect, 'https://red-social-android.vercel.app')

    expect(url.pathname).toBe('/login')
    expect(url.searchParams.get('next')).toBe('/app/reels/abc?resumeMs=5000#comments')
  })
})
