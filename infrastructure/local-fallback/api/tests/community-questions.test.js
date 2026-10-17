'use strict'
const test=require('node:test'),assert=require('node:assert/strict')
const {shortText,answerLimit,collectionPublicationAllowed}=require('../src/community-questions')
const {promotionInput}=require('../src/social-promotions')
test('question response limits count Unicode code points and reject controls',()=>{
  assert.equal(shortText(' 😀 '.repeat(20).trim().replaceAll(' ',''),20),'😀'.repeat(20))
  for(const text of ['','x'.repeat(41),'hi\nthere','bad\u0000text']) assert.throws(()=>shortText(text,40))
  assert.equal(shortText('  5  ',40),'5');assert.equal(shortText('e\u0301',1),'é')
  for(const value of [19,41,NaN,'40',20.5,null])assert.throws(()=>answerLimit(value))
  assert.equal(answerLimit(),40);assert.equal(answerLimit(20),20)
})
test('publication setting denies members while allowing the actual room creator',async()=>{
  const db={query:async()=>({rows:[{only_creator:true}]})}
  assert.equal(await collectionPublicationAllowed(db,'group','id',false),false)
  assert.equal(await collectionPublicationAllowed(db,'community','id',true),true)
  assert.equal(await collectionPublicationAllowed({query:async()=>({rows:[]})},'group','id',false),true)
})
test('promotions accept internal routes and HTTPS product destinations',()=>{
  assert.equal(promotionInput({title:'Novvin',destination:'/app/messages'}).destination,'/app/messages')
  for(const destination of ['//evil.test','http://shop.example','https://user:pass@shop.example','javascript:alert(1)','/app/\\evil.test'])assert.throws(()=>promotionInput({title:'Novvin',destination}))
  assert.throws(()=>promotionInput({title:'x'.repeat(101)}));assert.throws(()=>promotionInput({title:' '}))
})

test('carousel validates every card and enforces one to ten images',()=>{
 const card={title:'Producto',imageUrl:'https://media.example/card.png',destination:'https://shop.example/product',cta:'Comprar'}
 assert.equal(promotionInput({title:'Catálogo',cards:[card,card]}).cards.length,2)
 for(const cards of [[],Array(11).fill(card),[null],[{...card,imageUrl:''}],[{...card,destination:'javascript:alert(1)'}]])assert.throws(()=>promotionInput({title:'Catálogo',cards}))
 assert.equal(promotionInput({title:'Novvin'}).cards.length,0)
})
test('question preview is readable and never leaks the descriptor',()=>{
 const {previewFromMessage}=require('../src/data-routes')
 assert.equal(previewFromMessage({content_type:'question',content:JSON.stringify({questionId:'private-id',prompt:'2+2?'})}),'Pregunta: 2+2?')
 assert.equal(previewFromMessage({content_type:'question',content:'{bad'}),'Pregunta de la comunidad')
})
