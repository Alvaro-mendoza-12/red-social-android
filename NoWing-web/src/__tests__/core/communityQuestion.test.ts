import {questionReference} from '../../core/communityQuestion'
const value={questionId:'00000000-0000-4000-8000-000000000001',prompt:'3 + 2',maxLength:40}
test('accepts valid persisted question references',()=>expect(questionReference(JSON.stringify(value))).toEqual(value))
test.each(['null','[]','plain text',JSON.stringify({...value,questionId:'../private'}),JSON.stringify({...value,maxLength:41}),JSON.stringify({...value,maxLength:19}),JSON.stringify({...value,prompt:''})])('rejects malformed question references: %s',content=>expect(questionReference(content)).toBeNull())
