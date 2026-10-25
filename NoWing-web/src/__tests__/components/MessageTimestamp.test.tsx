import { render } from '@testing-library/react'
import { MessageTimestamp } from '../../components/MessageTimestamp'

test('UTC and an explicit local offset display the same instant without applying a second offset', () => {
  const { container } = render(<><MessageTimestamp createdAt="2026-10-24T00:23:40.000Z" mine/><MessageTimestamp createdAt="2026-10-23T19:23:40.000-05:00" mine={false}/></>)
  const times = container.querySelectorAll('time')
  expect(times[0].textContent).toBe(times[1].textContent)
  expect(times[0]).toHaveAttribute('dateTime', '2026-10-24T00:23:40.000Z')
})
