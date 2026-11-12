import { sharedVideoReference } from '../../core/sharedVideoReference'
const id='18ac0a9c-079e-45ba-87bf-bbbe64073777'

test.each(['novvinapp.com','www.novvinapp.com'])('registered domain video references work only over HTTPS on %s',host=>{
  expect(sharedVideoReference(`https://${host}/app/reels/${id}`)?.videoId).toBe(id)
  expect(sharedVideoReference(`http://${host}/app/reels/${id}`)).toBeNull()
  expect(sharedVideoReference(`https://${host}.evil.test/app/reels/${id}`)).toBeNull()
})
test('mobile video shares become one concise web card',()=>{
  expect(sharedVideoReference(`¡Mira este video!\nhttps://red-social-android.vercel.app/app/reels/${id}\n\nPrueba`)).toEqual({videoId:id,caption:'Prueba'})
})
test('legacy Novvin links and native links remain compatible',()=>{
  expect(sharedVideoReference(`https://conectate.com/video/${id}`)?.videoId).toBe(id)
  expect(sharedVideoReference(`conectate://video/${id}`)?.videoId).toBe(id)
})
test('untrusted hosts, credentials and alternate ports never become previews',()=>{
  for(const link of [`https://attacker.test/app/reels/${id}`,`https://user:secret@red-social-android.vercel.app/app/reels/${id}`,`https://red-social-android.vercel.app:8443/app/reels/${id}`])expect(sharedVideoReference(link)).toBeNull()
})
test('multiple links and invalid video identifiers remain ordinary text',()=>{
  expect(sharedVideoReference(`https://red-social-android.vercel.app/app/reels/${id} https://example.test`)).toBeNull()
  expect(sharedVideoReference('https://red-social-android.vercel.app/app/reels/no')).toBeNull()
})
test('caption length is bounded and clip parameters do not change the identity',()=>{
  expect(sharedVideoReference(`https://red-social-android.vercel.app/app/reels/${id}?resumeMs=5000&endMs=10000 ${'a'.repeat(900)}`)).toEqual({videoId:id,caption:'a'.repeat(800)})
})
