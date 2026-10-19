import { spellingWordAt, replaceSpellingWord } from '../../core/spellingWord'
test('selects a word at the caret and applies a suggestion without changing other text',()=>{
 const text='Hola, nesecito ayuda',issue=spellingWordAt(text,12)!
 expect(issue.word).toBe('nesecito')
 expect(replaceSpellingWord(text,issue,'necesito')).toEqual({text:'Hola, necesito ayuda',caret:14})
 expect(replaceSpellingWord('Cambio de texto',issue,'necesito')).toBeNull()
})
test('handles unicode and leaves links, handles, files, codes and oversized drafts alone',()=>{
 expect(spellingWordAt('publicación',8)?.word).toBe('publicación')
 expect(spellingWordAt('https://example.org\nrecivir')?.word).toBe('recivir')
 for(const text of ['https://example.org/foto','@nesecito','abc123','documento.pdf/archivo','a'.repeat(5000)])expect(spellingWordAt(text)).toBeNull()
})
