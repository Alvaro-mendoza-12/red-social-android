export function safeAccent(value: unknown) { return typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value) ? value : '#8acfff' }
/** Keep the chosen hue while ensuring readable text and button contrast. */
export function accentInk(value: string, dark: boolean) {
  const source = safeAccent(value).slice(1).match(/../g)!.map(part => parseInt(part, 16))
  const luminance = (rgb: number[]) => rgb.map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4).reduce((n,v,i) => n + v * [.2126,.7152,.0722][i], 0)
  const background = dark ? .016 : 1
  for (let step = 0; step <= 20; step++) {
    const rgb = source.map(v => Math.round(v + ((dark ? 255 : 0) - v) * step / 20)), l = luminance(rgb)
    const ratio = (Math.max(l,background) + .05) / (Math.min(l,background) + .05)
    if (ratio >= (dark ? 6 : 4.5)) return '#' + rgb.map(v => v.toString(16).padStart(2,'0')).join('')
  }
  return dark ? '#ffffff' : '#000000'
}
