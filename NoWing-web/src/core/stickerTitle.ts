/** Legacy API placeholders are not useful names for a sticker. */
export function stickerTitle(value: string) {
  const clean=value.trim()
  return !clean || /^(string|undefined|null|\[object object\])$/i.test(clean) ? 'Sticker' : clean
}
