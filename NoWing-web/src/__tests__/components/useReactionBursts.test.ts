import { act, renderHook } from '@testing-library/react'
import { useReactionBursts } from '../../components/useReactionBursts'

afterEach(() => jest.useRealTimers())

test('a reaction stays visible past four seconds and expires after its complete flight', () => {
  jest.useFakeTimers()
  const { result } = renderHook(() => useReactionBursts('room-a'))
  act(() => result.current.add({id:'one',emoji:'❤️'}))
  act(() => jest.advanceTimersByTime(4000))
  expect(result.current.items).toHaveLength(1)
  act(() => result.current.add({id:'one',emoji:'❤️'}))
  expect(result.current.items).toHaveLength(1) // Socket echo cannot restart the animation.
  act(() => jest.advanceTimersByTime(2200))
  expect(result.current.items).toHaveLength(0)
})

test('changing rooms clears the previous reactions and their pending cleanup', () => {
  jest.useFakeTimers()
  const { result, rerender, unmount } = renderHook(({room}) => useReactionBursts(room), {initialProps:{room:'a'}})
  act(() => result.current.add({id:'one',emoji:'👏'}))
  rerender({room:'b'})
  expect(result.current.items).toHaveLength(0)
  act(() => result.current.add({id:'one',emoji:'👏'}))
  expect(result.current.items).toHaveLength(1)
  unmount()
  expect(jest.getTimerCount()).toBe(0)
})

test('rapid reactions from the same participant keep only one visible at a time without timer leaks', () => {
  jest.useFakeTimers()
  const { result, unmount } = renderHook(() => useReactionBursts('room-burst'))
  act(() => result.current.add({ id: 'r1', emoji: '🔥', userId: 'user-1' }))
  act(() => result.current.add({ id: 'r2', emoji: '❤️', userId: 'user-1' }))
  act(() => result.current.add({ id: 'r3', emoji: '👏', userId: 'user-2' }))
  expect(result.current.items).toEqual([
    { id: 'r2', emoji: '❤️', userId: 'user-1' },
    { id: 'r3', emoji: '👏', userId: 'user-2' },
  ])
  expect(jest.getTimerCount()).toBe(2)
  unmount()
  expect(jest.getTimerCount()).toBe(0)
})
