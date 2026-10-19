export function messageLocationUrl(raw: string | null): string | null {
  try {
    const url = new URL(raw || '')
    if (url.protocol !== 'https:' || url.hostname !== 'maps.google.com' || url.username || url.password || url.port || url.hash || url.pathname !== '/' || url.searchParams.getAll('q').length !== 1 || [...url.searchParams.keys()].some(key => key !== 'q')) return null
    const parts = (url.searchParams.get('q') || '').split(',')
    if (parts.length !== 2 || parts.some(part => !/^[+-]?\d{1,3}(?:\.\d{1,12})?$/.test(part))) return null
    const [lat, lon] = parts.map(Number)
    return Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? url.href : null
  } catch { return null }
}
