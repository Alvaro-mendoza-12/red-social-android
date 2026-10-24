import { animatedImageFile, imageEditsAreEmpty } from '../../core/imageEditPolicy'

test('confirming an untouched sticker preserves the original, while actual edits are detected', () => {
  const full = { x: 0, y: 0, w: 1, h: 1 }
  expect(imageEditsAreEmpty(0, false, '', false, full)).toBe(true)
  expect(imageEditsAreEmpty(360, false, '', true, full)).toBe(true)
  expect(imageEditsAreEmpty(90, false, '', false, full)).toBe(false)
  expect(imageEditsAreEmpty(0, true, '', false, full)).toBe(false)
  expect(imageEditsAreEmpty(0, false, 'Texto', false, full)).toBe(false)
  expect(imageEditsAreEmpty(0, false, '', true, { ...full, w: .5 })).toBe(false)
})
test('animation detection reads original GIF, animated WebP and APNG bytes, not their extension', async () => {
  const file = (data: number[] | string) => ({ slice: () => ({ arrayBuffer: async () => typeof data === 'string' ? new TextEncoder().encode(data).buffer : new Uint8Array(data).buffer }) }) as File
  expect(await animatedImageFile(file('GIF89a-original'))).toBe(true)
  expect(await animatedImageFile(file('RIFFxxxxWEBPANIM\0\0\0\0'))).toBe(true)
  expect(await animatedImageFile(file([137,80,78,71,13,10,26,10,0,0,0,0,97,99,84,76,0,0,0,0]))).toBe(true)
  expect(await animatedImageFile(file('static-image'))).toBe(false)
})
