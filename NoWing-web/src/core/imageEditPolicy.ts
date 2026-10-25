import type { ImageSelection } from './imageCrop'

/** Confirming an untouched image must not flatten GIF/WebP/APNG into a PNG. */
export function imageEditsAreEmpty(rotation: number, flip: boolean, text: string, cropping: boolean, selection: ImageSelection) {
  return rotation % 360 === 0 && !flip && !text.trim() && (!cropping ||
    selection.x === 0 && selection.y === 0 && selection.w === 1 && selection.h === 1)
}

export async function animatedImageFile(file: File): Promise<boolean> {
  const bytes = new Uint8Array(await file.slice(0, 1024 * 1024).arrayBuffer())
  const ascii = (start: number, size: number) => String.fromCharCode(...bytes.subarray(start, start + size))
  if (ascii(0, 3) === 'GIF') return true
  if (ascii(0, 4) === 'RIFF' && ascii(8, 4) === 'WEBP') {
    for (let i = 12; i + 8 <= bytes.length;) {
      const chunk = ascii(i, 4)
      if (chunk === 'ANIM' || chunk === 'ANMF' || chunk === 'VP8X' && (bytes[i + 8] & 2) !== 0) return true
      const size = new DataView(bytes.buffer).getUint32(i + 4, true)
      i += 8 + size + (size % 2)
    }
  }
  if (bytes[0] === 137 && ascii(1, 3) === 'PNG') {
    for (let i = 8; i + 12 <= bytes.length;) {
      const size = new DataView(bytes.buffer).getUint32(i)
      if (ascii(i + 4, 4) === 'acTL') return true
      if (ascii(i + 4, 4) === 'IDAT') return false
      i += size + 12
    }
  }
  return false
}
