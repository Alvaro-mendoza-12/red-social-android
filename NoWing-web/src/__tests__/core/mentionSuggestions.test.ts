import { insertMention, matchingMentions, mentionQuery } from '../../core/mentionSuggestions'

const people = [{ id: '1', name: 'Juan Pérez', username: 'juan' }, { id: '2', name: 'Ana', username: 'ana' }]
test('only a trailing mention opens matching suggestions', () => {
  expect(mentionQuery('Voy con @ju')).toBe('ju')
  expect(mentionQuery('correo@ejemplo.com')).toBeNull()
  expect(mentionQuery('Ya @juan respondió')).toBeNull()
  expect(matchingMentions('Voy con @ju', people)).toEqual([people[0]])
})
test('selection uses the canonical public handle and keeps the preceding draft', () => {
  expect(insertMention('Voy con @ju', 'juan')).toBe('Voy con @juan ')
  expect(matchingMentions('@', people, '1')).toEqual([people[1]])
})
