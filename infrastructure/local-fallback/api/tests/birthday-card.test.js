const test = require('node:test')
const assert = require('node:assert/strict')
const { birthdayCardContent } = require('../src/birthday-card')
const { profileForViewer, normalizeProfileSectionVisibility, profilePatchFromBody, previewFromMessage } = require('../src/data-routes')
const value = { version: 1, kind: 'birthday', message: ' ¡Feliz cumpleaños! 🎂 ', theme: 'confetti' }
test('birthday letters canonicalize only supported content', () => {
  assert.deepEqual(JSON.parse(birthdayCardContent(JSON.stringify({ ...value, url: 'https://invalid.example' }))), { ...value, message: value.message.trim() })
  assert.equal(previewFromMessage({ content_type: 'birthday_card', content: JSON.stringify(value) }), '🎂 Tarjeta de cumpleaños')
  assert.equal(previewFromMessage({ content_type: 'birthday_card', deleted_for_all: true }), 'Mensaje eliminado')
})
test('birthday letters reject arbitrary payloads, media and collection targets', () => {
  for (const content of ['bad', 'null', JSON.stringify({ ...value, kind: 'other' }), JSON.stringify({ ...value, theme: 'html' }), JSON.stringify({ ...value, message: '😀'.repeat(601) }), JSON.stringify({ ...value, message: '' })]) assert.throws(() => birthdayCardContent(content), /BIRTHDAY_CARD_INVALID/)
  for (const options of [{ kind: 'group' }, { kind: 'community' }, { mediaUrl: 'image' }, { pollId: 'poll' }]) assert.throws(() => birthdayCardContent(JSON.stringify(value), options))
  assert.doesNotThrow(() => birthdayCardContent(JSON.stringify({ ...value, message: '😀'.repeat(600) })))
})
test('birthday visibility defaults on, and server filters hidden birthday and education', () => {
  assert.equal(normalizeProfileSectionVisibility({}).birthday, 'everyone')
  const profile = { birth_date: '2000-10-17', education: 'Universidad', workplace: 'Empresa', career: 'Ingeniería', profile_section_visibility: { birthday: 'only_me', education: 'only_me', career: 'only_me' } }
  const visible = profileForViewer(profile)
  assert.equal(visible.birth_date, ''); assert.equal(visible.education, ''); assert.equal(visible.workplace, '')
  assert.equal(profileForViewer(profile, { viewerIsTarget: true }).birth_date, profile.birth_date)
  assert.equal(profile.birth_date, '2000-10-17')
  assert.equal(profileForViewer({ ...profile, profile_section_visibility: { birthday: 'followers' } }, { isFollowing: true }).birth_date, profile.birth_date)
  assert.equal(profileForViewer({ ...profile, profile_section_visibility: { birthday: 'followers' } }).birth_date, '')
})
test('presentation edits keep education, workplace and birthday privacy', () => {
  const patch = profilePatchFromBody({ education: 'Universidad', workplace: 'Empresa', profile_section_visibility: { birthday: 'only_me' } })
  assert.equal(patch.education, 'Universidad'); assert.equal(patch.workplace, 'Empresa'); assert.equal(patch.profile_section_visibility.birthday, 'only_me')
})

test('public profile projection retains birthday before applying viewer privacy', () => {
 const {normalizeProfileForPublic}=require('../src/data-routes')
 const raw=normalizeProfileForPublic({id:'profile-birthday',birth_date:'2000-10-17',profile_section_visibility:{birthday:'only_me'}})
 assert.equal(raw.birth_date,'2000-10-17')
 assert.equal(profileForViewer(raw).birth_date,'')
 assert.equal(profileForViewer(raw,{viewerIsTarget:true}).birth_date,'2000-10-17')
})
