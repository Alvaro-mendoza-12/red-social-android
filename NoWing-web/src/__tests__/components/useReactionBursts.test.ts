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
