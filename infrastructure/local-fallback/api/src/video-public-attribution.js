const originalSoundPrefix = 'Sonido original — '
const emailAddress = /^[^\s@]+@[^\s@]+\.[^\s@]+$/u

function publicVideoSound(title, artist, fallbackArtist) {
  const publicArtist = artist && !emailAddress.test(artist.trim()) ? artist : fallbackArtist
  const cleanTitle = title.trim()
  const originalCredit = cleanTitle.startsWith(originalSoundPrefix)
    ? cleanTitle.slice(originalSoundPrefix.length).trim()
    : ''
  const publicTitle = cleanTitle && !emailAddress.test(cleanTitle) && !emailAddress.test(originalCredit)
    ? title
    : originalSoundPrefix + publicArtist
  return { soundArtist: publicArtist, soundTitle: publicTitle }
}

module.exports = { publicVideoSound }
