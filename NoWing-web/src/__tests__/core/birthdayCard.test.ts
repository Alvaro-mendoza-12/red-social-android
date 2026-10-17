import { birthdayCard, birthdayLabel, defaultBirthdayCard } from '../../core/birthdayCard'
test('birthday card is strict and preserves Unicode', () => {
 expect(birthdayCard(JSON.stringify(defaultBirthdayCard))).toEqual(defaultBirthdayCard)
 for (const value of ['null','bad',JSON.stringify({...defaultBirthdayCard,kind:'html'}),JSON.stringify({...defaultBirthdayCard,message:'😀'.repeat(601)}),JSON.stringify({...defaultBirthdayCard,theme:'other'})]) expect(birthdayCard(value)).toBeNull()
 expect(birthdayCard(JSON.stringify({...defaultBirthdayCard,message:'😀'.repeat(600)}))).not.toBeNull()
})
test('birthday presentation omits year and rejects impossible dates', () => {
 expect(birthdayLabel('2000-10-17')).toBe('17 de octubre')
 expect(birthdayLabel('2000-02-31')).toBe('')
 expect(birthdayLabel('bad')).toBe('')
})
