import { voiceIsAudible } from '../../features/voice/voiceActivity'
const tone = (amplitude: number) => Uint8Array.from({ length: 256 }, (_, i) => Math.round(128 + amplitude * 128 * Math.sin(i * Math.PI / 16)))
test('audible narrow-band audio is detected', () => expect(voiceIsAudible(tone(.2), false)).toBe(true))
test('quiet background noise does not mark the speaker active', () => expect(voiceIsAudible(tone(.003), false)).toBe(false))
test('silence and absent samples remain inactive', () => { expect(voiceIsAudible(new Uint8Array(256).fill(128), false)).toBe(false); expect(voiceIsAudible(new Uint8Array(), false)).toBe(false) })
test('muting immediately suppresses the indicator despite remaining samples', () => expect(voiceIsAudible(tone(.5), true)).toBe(false))
