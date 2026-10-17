import { mergeMessageSnapshot, newerMessage } from '../../core/conversationMessageMerge'
const original={id:'m',updatedAt:'2026-10-17T12:00:00Z',status:'sent',isEdited:false,deletedForAll:false,content:'Antes'}
const edited={...original,updatedAt:'2026-10-17T12:01:00Z',isEdited:true,content:'Corrección'}
test('late snapshots and realtime receipts cannot roll an edit back',()=>{
  expect(newerMessage(edited,original)).toEqual(edited)
  expect(mergeMessageSnapshot([edited],[original])).toEqual([edited])
  expect(newerMessage(original,edited)).toEqual(edited)
})
test('does not restore a cancelled outbox version after server confirmation',()=>{
  expect(newerMessage(edited,{...original,status:'pending',updatedAt:'2030-01-01'})).toEqual(edited)
  expect(newerMessage({...original,status:'pending'},edited)).toEqual(edited)
})
test('keeps deletion tombstones and respects rows hidden in the server snapshot',()=>{
  const deleted={...edited,deletedForAll:true,content:''}
  expect(newerMessage(deleted,edited)).toEqual(deleted)
  expect(mergeMessageSnapshot([edited],[])).toEqual([])
})
