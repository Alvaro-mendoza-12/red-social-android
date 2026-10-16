export type ImageSelection = { x: number; y: number; w: number; h: number }
const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value))
export function cropSelection(s: ImageSelection, mode: string, dx: number, dy: number): ImageSelection {
  if (mode === 'move') return { ...s, x: clamp(s.x + dx, 0, 1 - s.w), y: clamp(s.y + dy, 0, 1 - s.h) }
  if (mode === 'draw') {
    const originX=clamp(s.x,0,.99),originY=clamp(s.y,0,.99),endX = clamp(s.x + dx, 0, 1), endY = clamp(s.y + dy, 0, 1)
    return { x: Math.min(originX, endX), y: Math.min(originY, endY), w: Math.max(.01, Math.abs(endX - originX)), h: Math.max(.01, Math.abs(endY - originY)) }
  }
  const left = mode.includes('w') ? clamp(s.x + dx, 0, s.x + s.w - .01) : s.x
  const right = mode.includes('e') ? clamp(s.x + s.w + dx, s.x + .01, 1) : s.x + s.w
  const top = mode.includes('n') ? clamp(s.y + dy, 0, s.y + s.h - .01) : s.y
  const bottom = mode.includes('s') ? clamp(s.y + s.h + dy, s.y + .01, 1) : s.y + s.h
  return { x: left, y: top, w: right - left, h: bottom - top }
}
