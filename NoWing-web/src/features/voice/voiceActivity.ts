/** Time-domain energy detects speech and narrow-band audio without diluting it across FFT bins. */
export function voiceIsAudible(samples: Uint8Array, muted: boolean): boolean {
  if (muted || !samples.length) return false
  let energy = 0
  for (const sample of samples) energy += ((sample - 128) / 128) ** 2
  return Math.sqrt(energy / samples.length) > 0.015
}
