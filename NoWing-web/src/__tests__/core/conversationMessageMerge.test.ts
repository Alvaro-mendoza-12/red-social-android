import { mergeMessageSnapshot, mergeOlderMessagePage, mergeLatestMessagePage, newerMessage } from '../../core/conversationMessageMerge'
const original={id:'m',updatedAt:'2026-10-17T12:00:00Z',status:'sent',isEdited:false,deletedForAll:false,content:'Antes'}
const edited={...original,updatedAt:'2026-10-17T12:01:00Z',isEdited:true,content:'Corrección'}
test('late snapshots and realtime receipts cannot roll an edit back',()=>{
  expect(newerMessage(edited,original)).toEqual(edited)
  expect(mergeMessageSnapshot([edited],[original])).toEqual([edited])
  expect(newerMessage(original,edited)).toEqual(edited)
})

test('latest-page refresh retains previously loaded older messages but removes hidden rows in its covered interval', () => {
  const older = { ...original, id: 'old', createdAt: '2026-10-17T10:00:00Z' }
  const recent = { ...original, id: 'recent', createdAt: '2026-10-17T12:00:00Z' }
  const hidden = { ...original, id: 'hidden', createdAt: '2026-10-17T13:00:00Z' }
  expect(mergeLatestMessagePage([older, recent, hidden], [recent], true)).toEqual([older, recent])
  expect(mergeLatestMessagePage([older, recent, hidden], [], false)).toEqual([])
})

test('messages created within the same millisecond keep PostgreSQL microsecond order', () => {
  const earlier = { ...original, id: 'z', createdAt: '2026-10-17T12:00:00.123001Z' }
  const later = { ...original, id: 'a', createdAt: '2026-10-17T12:00:00.123999Z' }
  expect(mergeOlderMessagePage([later], [earlier])).toEqual([earlier, later])
})

test('an in-flight snapshot cannot erase a newer realtime insert while removing a known hidden row', () => {
  const known = { ...original, id: 'known', createdAt: '2026-10-17T12:00:00Z' }
  const hidden = { ...known, id: 'hidden' }
  const live = { ...known, id: 'live', createdAt: '2026-10-17T12:01:00Z' }
  const atStart = new Set([known.id, hidden.id])
  expect(mergeLatestMessagePage([known, hidden, live], [known], false, atStart)).toEqual([known, live])
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
test('prepends older pages in chronological order without losing realtime messages or newer edits',()=>{
  const older={...original,id:'older',createdAt:'2026-10-17T11:00:00Z',content:'Anterior'}
  const current={...edited,createdAt:'2026-10-17T12:00:00Z'}
  const incomingStale={...original,createdAt:'2026-10-17T12:00:00Z'}
  const realtime={...original,id:'realtime',createdAt:'2026-10-17T12:02:00Z'}
  expect(mergeOlderMessagePage([current,realtime],[older,incomingStale])).toEqual([older,current,realtime])
})
