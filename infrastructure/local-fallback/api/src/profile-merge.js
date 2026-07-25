const ABSENT_TEXT_VALUES = new Set(['null', 'undefined'])
const ABSENT_MEDIA_TEXT_VALUES = new Set(['none', 'unknown', 'default', 'n/a'])
const MEDIA_FIELD_PATTERN = /(avatar|cover|banner|photo|picture|profile_image|thumbnail|media)/i
const MEDIA_METADATA_PATTERN = /(url|uri|path|type|mime|duration|width|height|size|muted|metadata|image)/i
const MEDIA_NUMERIC_METADATA_PATTERN = /(duration|width|height|size|bitrate|length)/i

function isMediaMetadataField(key) {
  const field = String(key || '')
  return MEDIA_FIELD_PATTERN.test(field) && MEDIA_METADATA_PATTERN.test(field)
}

function isMeaningfulProfileValue(key, value, existingValue) {
  if (value == null) return false

  if (typeof value === 'string') {
    const cleanValue = value.trim()
    if (!cleanValue) return false

    const normalizedValue = cleanValue.toLowerCase()
    if (ABSENT_TEXT_VALUES.has(normalizedValue)) return false
    if (isMediaMetadataField(key) && ABSENT_MEDIA_TEXT_VALUES.has(normalizedValue)) return false

    if (
      isMediaMetadataField(key) &&
      MEDIA_NUMERIC_METADATA_PATTERN.test(String(key)) &&
      Number(cleanValue) <= 0 &&
      Number(existingValue) > 0
    ) {
      return false
    }
    return true
  }

  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return false
    if (
      isMediaMetadataField(key) &&
      MEDIA_NUMERIC_METADATA_PATTERN.test(String(key)) &&
      value <= 0 &&
      Number(existingValue) > 0
    ) {
      return false
    }
    return true
  }

  if (Array.isArray(value)) return value.length > 0
  if (value instanceof Date) return Number.isFinite(value.getTime())
  if (typeof value === 'object') return Object.keys(value).length > 0

  // Explicit booleans are meaningful (for example is_private=false or muted=false).
  return typeof value === 'boolean'
}

function mergeProfileRows(baseRow, preferredRow) {
  const merged = {
    ...(baseRow && typeof baseRow === 'object' ? baseRow : {}),
  }

  const clearFields = new Set(
    Array.isArray(preferredRow?.__clear_fields)
      ? preferredRow.__clear_fields.map((value) => String(value || '').trim()).filter(Boolean)
      : [],
  )

  for (const [key, value] of Object.entries(
    preferredRow && typeof preferredRow === 'object' ? preferredRow : {},
  )) {
    if (key === '__clear_fields') continue
    if (clearFields.has(key)) {
      merged[key] = ''
      continue
    }
    if (isMeaningfulProfileValue(key, value, merged[key])) merged[key] = value
  }

  return merged
}

module.exports = {
  isMeaningfulProfileValue,
  mergeProfileRows,
}
