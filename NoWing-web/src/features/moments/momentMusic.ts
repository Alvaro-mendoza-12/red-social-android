import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import type { GenericRow } from '../../core/utils'

export type MomentTrack = {
  id: string; title: string; artist: string; audioUrl: string; durationMs: number
  source: 'novvin_community' | 'novvin_moment_local'; artworkUrl?: string; sourceUrl?: string; localFile?: File
}
export type MomentMusicSelection = {
  track: MomentTrack; startMs: number; volume: number; originalVolume: number
  fadeInMs: number; fadeOutMs: number; duckOriginal: boolean; presentation: 'card' | 'label' | 'audio'
}
export type MomentSound = {
  audioUrl: string; title: string; artist: string; startMs: number; durationMs: number; embedded: boolean
  volume: number; originalVolume: number; fadeInMs: number; fadeOutMs: number; duckOriginal: boolean
  presentation: 'card' | 'label' | 'audio' | 'lyrics'; artworkUrl?: string
}
export type MomentOverlay = { text: string; color: string; font: 'sans' | 'serif' | 'mono'; x: number; y: number }
const finite = (value: number, fallback: number) => Number.isFinite(value) ? value : fallback
export const unit = (value: number) => Math.min(1, Math.max(0, finite(value, 1)))
export function momentFileError(file: Pick<File, 'type' | 'size'>): string {
  if (!['image/jpeg', 'image/png', 'image/webp', 'video/mp4'].includes(file.type)) return 'Elige una foto JPG, PNG o WEBP, o un video MP4.'
  return file.size > 35 * 1024 * 1024 ? 'El archivo puede tener hasta 35 MB.' : ''
}
export function momentDuration(mediaType: string, durationMs?: number): number {
  return mediaType === 'video' ? Math.max(1000, Math.min(25000, finite(durationMs || 25000, 25000))) : 6000
}
export function musicRange(sourceMs: number, contentMs: number, desiredStart: number) {
  const source = Math.max(1000, finite(sourceMs, 1000)), length = Math.min(source, Math.max(1000, finite(contentMs, 6000)))
  const startMs = Math.round(Math.max(0, Math.min(finite(desiredStart, 0), source - length)))
  return { startMs, endMs: startMs + length, durationMs: length, maxStartMs: Math.max(0, source - length) }
}
export function musicGain(sound: Pick<MomentSound, 'volume' | 'durationMs' | 'fadeInMs' | 'fadeOutMs'>, elapsedMs: number) {
  if (elapsedMs < 0 || elapsedMs >= sound.durationMs) return 0
  const limit = sound.durationMs / 2
  const fadeIn = Math.max(0, Math.min(finite(sound.fadeInMs, 0), limit)), fadeOut = Math.max(0, Math.min(finite(sound.fadeOutMs, 0), limit))
  return unit(sound.volume) * Math.min(1, fadeIn > 0 ? elapsedMs / fadeIn : 1, fadeOut > 0 ? (sound.durationMs - elapsedMs) / fadeOut : 1)
}
export function musicPublishFields(selection: MomentMusicSelection | undefined, contentMs: number, uploadedUrl?: string) {
  if (!selection) return {}
  const range = musicRange(selection.track.durationMs, contentMs, selection.startMs)
  return { musicId: selection.track.id, musicSource: selection.track.source, musicAudioUrl: uploadedUrl || selection.track.audioUrl,
    musicTitle: selection.track.title, musicArtist: selection.track.artist, musicSourceUrl: selection.track.sourceUrl || '',
    musicStartMs: range.startMs, musicEndMs: range.endMs, musicDurationMs: range.durationMs,
    musicVolume: unit(selection.volume), originalAudioVolume: unit(selection.originalVolume),
    musicFadeInMs: Math.min(Math.max(0, finite(selection.fadeInMs, 0)), 5000, range.durationMs / 2),
    musicFadeOutMs: Math.min(Math.max(0, finite(selection.fadeOutMs, 0)), 5000, range.durationMs / 2),
    duckOriginalAudio: selection.duckOriginal, musicPresentation: selection.presentation, musicEmbedded: false }
}
export async function searchMomentMusic(query: string, cursor?: string, signal?: AbortSignal) {
  const params = new URLSearchParams({ q: query.trim().slice(0, 100), limit: '20' })
  if (cursor) params.set('cursor', cursor)
  const result = await fallbackApiJson<{ items?: GenericRow[]; nextCursor?: string }>(`/v1/data/music/search?${params}`, { signal })
  const tracks: MomentTrack[] = (result.items || []).filter(row => row.moderationState === 'APPROVED' && row.allowUseInMoments === true && row.allowReuse === true &&
    ['PUBLIC_REUSABLE', 'PUBLIC_REMIXABLE'].includes(String(row.visibility)) && Number.isFinite(Number(row.durationMs)) && Number(row.durationMs) >= 6000 &&
    String(row.id).startsWith('community:') && typeof row.audioUrl === 'string' && row.audioUrl.trim().length > 0).map(row => ({
      id: String(row.id), title: String(row.title || 'Audio'), artist: String(row.artist || 'Artista'), audioUrl: String(row.audioUrl),
      durationMs: Number(row.durationMs), source: 'novvin_community', artworkUrl: String(row.coverUrl || ''), sourceUrl: String(row.sourcePageUrl || ''),
    }))
  return { tracks, nextCursor: result.nextCursor || '' }
}
export function mapMomentSound(row: GenericRow): MomentSound | undefined {
  const audioUrl = String(row.music_audio_url || row.music_url || '')
  if (!audioUrl) return undefined
  return { audioUrl, title: String(row.music_title || 'Música'), artist: String(row.music_artist || ''), startMs: Math.max(0, Number(row.music_start_ms) || 0),
    durationMs: Math.max(1000, Math.min(60000, Number(row.music_duration_ms) || 6000)), embedded: row.music_embedded === true,
    volume: unit(Number(row.music_volume ?? 1)), originalVolume: unit(Number(row.original_audio_volume ?? 1)),
    fadeInMs: Math.max(0, Number(row.music_fade_in_ms) || 0), fadeOutMs: Math.max(0, Number(row.music_fade_out_ms) || 0),
    duckOriginal: row.duck_original_audio === true, presentation: ['label', 'audio', 'lyrics'].includes(String(row.music_presentation)) ? row.music_presentation as MomentSound['presentation'] : 'card',
    artworkUrl: String(row.music_artwork_url || '') }
}
export function mapMomentOverlay(row: GenericRow): MomentOverlay | undefined {
  if (!row.overlay_text) return undefined
  const raw = String(row.overlay_color || '')
  return { text: String(row.overlay_text).slice(0, 120), color: /^#[0-9a-f]{8}$/i.test(raw) ? `#${raw.slice(3)}${raw.slice(1, 3)}` : '#ffffff',
    font: ['serif', 'mono'].includes(String(row.overlay_font)) ? row.overlay_font as MomentOverlay['font'] : 'sans', x: unit(Number(row.overlay_x ?? .5)), y: unit(Number(row.overlay_y ?? .38)) }
}
