import {
  avatarMediaTypeSource,
  conversationAvatarSource,
  findContentAvatarCandidate,
  firstMediaSource,
  isVideoAvatarSource,
  profileAvatarSource,
  profileCoverSource,
  profileIdentityAliases,
  profileVideoCoverSource,
  profileVideoSource,
  reelAuthorAvatarSource,
} from '../../core/profileMediaFields'

describe('profile media field resolution', () => {
  it('accepts snake_case and camelCase avatar fields', () => {
    expect(profileAvatarSource({ avatar_url: ' avatars/alvaro.webp ' })).toBe('avatars/alvaro.webp')
    expect(profileAvatarSource({ avatarUrl: 'avatars/adolf.webp' })).toBe('avatars/adolf.webp')
    expect(profileAvatarSource({ photoUrl: 'avatars/mendoza.webp' })).toBe('avatars/mendoza.webp')
  })

  it('resolves nested and object-shaped avatar values', () => {
    expect(profileAvatarSource({ profile: { profileImage: { publicUrl: '/storage/profile.webp' } } })).toBe(
      '/storage/profile.webp',
    )
    expect(conversationAvatarSource({ otherParticipant: { avatarUrl: 'avatars/friend.webp' } })).toBe(
      'avatars/friend.webp',
    )
  })

  it('prefers author-specific avatar fields on reels', () => {
    expect(
      reelAuthorAvatarSource({
        avatarUrl: 'avatars/generic.webp',
        userProfileImage: 'avatars/author.webp',
      }),
    ).toBe('avatars/author.webp')
  })

  it('normalizes profile cover and video field variants', () => {
    expect(profileCoverSource({ bannerUrl: 'covers/banner.webp' })).toBe('covers/banner.webp')
    expect(profileVideoSource({ mediaUrl: 'videos/clip.mp4' })).toBe('videos/clip.mp4')
    expect(profileVideoCoverSource({ poster_url: 'thumbnails/clip.webp' })).toBe('thumbnails/clip.webp')
  })

  it('uses a fallback only when no supported source exists', () => {
    expect(firstMediaSource({ avatarUrl: '' }, ['avatarUrl'], ' avatars/fallback.webp ')).toBe(
      'avatars/fallback.webp',
    )
  })

  it('detects video avatars from media type or file extension', () => {
    expect(isVideoAvatarSource('avatars/live-avatar.mp4?v=2')).toBe(true)
    expect(isVideoAvatarSource('avatars/live-avatar.bin', 'video/webm')).toBe(true)
    expect(isVideoAvatarSource('avatars/photo.webp', 'image/webp')).toBe(false)
    expect(avatarMediaTypeSource({}, 'avatars/live-avatar.webm')).toBe('video/webm')
  })

  it('finds a real avatar in content for the same profile alias', () => {
    const profile = { id: 'uuid-adolf', username: 'Adolf' }
    const rows = [
      { user_id: 'other', user_profile_image: 'avatars/other.webp' },
      {
        userId: 'UUID-ADOLF',
        userProfileImage: 'avatars/adolf.mp4',
        avatarMediaType: 'video/mp4',
      },
    ]

    expect(findContentAvatarCandidate(rows, profileIdentityAliases(profile, ['Adolf']))).toEqual({
      url: 'avatars/adolf.mp4',
      mediaType: 'video/mp4',
    })
  })
})
