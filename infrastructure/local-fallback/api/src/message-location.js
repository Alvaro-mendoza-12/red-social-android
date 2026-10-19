/** A location is a map link, never a downloadable or executable attachment. */
function isMessageLocationUrl(value) {
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hash ||
      url.hostname !== 'maps.google.com' || url.pathname !== '/' || [...url.searchParams.keys()].some(key => key !== 'q') || url.searchParams.getAll('q').length !== 1) return false
    const parts = (url.searchParams.get('q') || '').split(',')
    if (parts.length !== 2 || parts.some(part => !/^[+-]?\d{1,3}(?:\.\d{1,12})?$/.test(part))) return false
    const [lat, lon] = parts.map(Number)
    return Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
  } catch { return false }
}
module.exports = { isMessageLocationUrl }
