import { textOf, type GenericRow } from '../../core/utils'
import { rewriteRuntimeStorageUrl } from '../../core/runtimeBackend'

export type MediaType = 'image' | 'video' | 'file' | 'none'

export type MediaEntry = {
  url: string
  mediaType: Exclude<MediaType, 'none'>
}

const defaultSingleMediaKeys = [
  'media_url',
  'image_url',
  'cover_url',
  'video_url',
  'thumbnail_url',
]

function uniqueUrls(urls: string[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  urls.forEach((entry) => {
    const clean = entry.trim()
    if (!clean || seen.has(clean)) return
    seen.add(clean)
    result.push(clean)
  })
  return result
}

function parseMediaCandidate(value: unknown): string[] {
  if (!value) return []
  if (Array.isArray(value)) {
    const nested = value.flatMap((entry) => parseMediaCandidate(entry))
    return uniqueUrls(nested)
  }
  if (typeof value === 'string') {
    const clean = value.trim()
    if (!clean) return []
    if (clean.startsWith('[') || clean.startsWith('{')) {
      try {
        const parsed = JSON.parse(clean)
        return parseMediaCandidate(parsed)
      } catch {
        return [clean]
      }
    }
    return [clean]
  }
  if (typeof value === 'object') {
    const row = value as GenericRow
    const guessed = textOf(row, ['url', 'media_url', 'path', 'public_url'], '')
    return guessed ? [guessed] : []
  }
  return []
}

export function inferMediaType(url: string): MediaType {
  const clean = url.trim().toLowerCase()
  if (!clean) return 'none'
  if (
    clean.includes('.mp4') ||
    clean.includes('.mov') ||
    clean.includes('.webm') ||
    clean.includes('.m3u8') ||
    clean.includes('.mkv') ||
    clean.includes('.avi')
  ) {
    return 'video'
  }
  if (
    clean.includes('.jpg') ||
    clean.includes('.jpeg') ||
    clean.includes('.png') ||
    clean.includes('.gif') ||
    clean.includes('.webp') ||
    clean.includes('.avif') ||
    clean.includes('.heic')
  ) {
    return 'image'
  }
  return 'file'
}

export function extractMediaUrls(row: GenericRow, extraKeys: string[] = []): string[] {
  const urls: string[] = []

  const multiValueSources = [
    row.media_urls,
    row.mediaUrls,
    row.media,
  ]
  multiValueSources.forEach((candidate) => {
    urls.push(...parseMediaCandidate(candidate))
  })

  const allSingleKeys = [...defaultSingleMediaKeys, ...extraKeys]
  allSingleKeys.forEach((key) => {
    const value = row[key]
    urls.push(...parseMediaCandidate(value))
  })

  return uniqueUrls(urls).map(rewriteRuntimeStorageUrl)
}

export function extractMediaEntries(row: GenericRow, extraKeys: string[] = []): MediaEntry[] {
  return extractMediaUrls(row, extraKeys).map((url) => {
    const mediaType = inferMediaType(url)
    return {
      url,
      mediaType: mediaType === 'none' ? 'file' : mediaType,
    }
  })
}
