import { buildSignalingAffinity } from '../../core/signalingAffinity'

describe('buildSignalingAffinity', () => {
  it('routes every participant in the same realtime scope to the same replica', () => {
    expect(buildSignalingAffinity('voice', ' room-1 ')).toBe('voice:room-1')
    expect(buildSignalingAffinity('call', 'call-1')).toBe('call:call-1')
    expect(buildSignalingAffinity('live', 'live-1')).toBe('live:live-1')
  })

  it('rejects empty or attacker-sized scope ids', () => {
    expect(() => buildSignalingAffinity('voice', ' ')).toThrow('Invalid signaling scope id')
    expect(() => buildSignalingAffinity('voice', 'x'.repeat(161))).toThrow('Invalid signaling scope id')
  })
})
