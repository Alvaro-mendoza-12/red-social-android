'use strict'
const test=require('node:test'),assert=require('node:assert/strict')
const {profileHandle,mentionHandles,collectionMentionRecipients:resolve}=require('../src/collection-mentions')
const profiles=[{id:'a',display_name:'Álvaro'},{id:'b',display_name:'Juan Pérez'},{id:'c',display_name:'Ana',username:'anita',username_origin:'chosen'}]
const input={profiles,memberIds:['a','b','c'],actorIds:['a']}
test('room mentions resolve display fallbacks, chosen usernames and accents',()=>assert.deepEqual(resolve({...input,content:'@juan_perez @anita'}),['b','c']))
test('duplicate mention creates one recipient',()=>assert.deepEqual(resolve({...input,content:'@anita @ANITA @anita'}),['c']))
test('self and outsider mentions do not create recipients',()=>assert.deepEqual(resolve({...input,memberIds:['a','b'],content:'@alvaro @anita @desconocido'}),[]))
test('email text is not a mention',()=>assert.deepEqual(mentionHandles('hola ana@anita.com https://a@anita'),[]))
test('ambiguous public handles inside the room are not guessed',()=>assert.deepEqual(resolve({...input,profiles:[...profiles,{id:'d',display_name:'Ana',username:'anita',username_origin:'chosen'}],memberIds:['a','b','c','d'],content:'@anita'}),[]))
test('duplicate profile aliases with same ID do not make a handle ambiguous',()=>assert.deepEqual(resolve({...input,profiles:[...profiles,profiles[2]],content:'@anita'}),['c']))
test('legacy generated username equal to email local part uses public display fallback',()=>assert.equal(profileHandle({username:'juan',email:'juan@example.invalid',display_name:'Juan Pérez'}),'juan_perez'))
test('safe distinct legacy username is retained',()=>assert.equal(profileHandle({username:'juan',email:'different@example.invalid',display_name:'Juan Pérez'}),'juan'))
test('email display names never become exposed handles',()=>assert.equal(profileHandle({display_name:'secret@example.invalid'}),'usuario'))
test('mentions are bounded to eight distinct handles',()=>assert.equal(mentionHandles(Array.from({length:20},(_,i)=>'@person'+i).join(' ')).length,8))

test('an overlong token never mentions the owner of its first 24 characters',()=>{
 const handle='a'.repeat(24)
 assert.deepEqual(resolve({profiles:[{id:'target',username:handle,username_origin:'chosen'}],memberIds:['target'],content:'@'+handle+'x'}),[])
})
test('an overlong dotted handle never notifies a matching prefix',()=>assert.deepEqual(mentionHandles('@'+'a'.repeat(24)+'.tail'),[]))
test('sentence punctuation after a valid mention remains supported',()=>assert.deepEqual(resolve({...input,content:'Hola @anita. ¿Vienes? @juan_perez...'}),['c','b']))
