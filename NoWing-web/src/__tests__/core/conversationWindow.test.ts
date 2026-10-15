import { conversationWindow } from '../../core/conversationWindow'
test('initial window renders the newest messages in the original order', () => {
  expect(conversationWindow([1,2,3,4,5],2,false)).toEqual([4,5])
})
test('expanding the window preserves the newest messages and reveals earlier ones', () => {
  expect(conversationWindow([1,2,3,4,5],4,false)).toEqual([2,3,4,5])
})
test('search includes the full authorized history', () => {
  expect(conversationWindow([1,2,3,4,5],2,true)).toEqual([1,2,3,4,5])
})
test('invalid window limits stay bounded and empty history stays empty', () => {
  expect(conversationWindow([1,2],-1,false)).toEqual([2])
  expect(conversationWindow([],80,false)).toEqual([])
})
