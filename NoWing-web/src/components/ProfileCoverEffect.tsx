import { useEffect, useRef, useState, type CSSProperties } from 'react'
import './profileCoverEffect.css'
import { PROFILE_EFFECT_CHOICES, profileThemeColor } from './profileAppearanceOptions'

export function ProfileCoverEffect({ effect = 'none', theme = 'conectate', overlay = false }: { effect?: string; theme?: string; overlay?: boolean }) {
  const [paused, setPaused] = useState(false), [visible, setVisible] = useState(true)
  const root = useRef<HTMLDivElement>(null)
  const selected = PROFILE_EFFECT_CHOICES.some(([id]) => id === effect) ? effect : 'none'
  useEffect(() => {
    const element = root.current
    if (!element || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return <div ref={root} className={`profile-cover-effect${overlay ? ' profile-cover-effect-overlay' : ''}`} data-effect={selected} data-paused={paused || !visible} style={{ '--cover-accent': profileThemeColor(theme) } as CSSProperties}>
    <div className="cover-effect-art" aria-hidden="true">{Array.from({ length: 16 }, (_, index) => <span key={index} style={{ '--i': index, '--x': `${(index * 61 + 11) % 97}%`, '--y': `${(index * 37 + 17) % 87}%`, '--delay': `${-index * .57}s` } as CSSProperties}>{selected === 'sparkles' || selected === 'constellation' ? '✦' : ''}</span>)}</div>
    {selected !== 'none' ? <button type="button" className="cover-motion-toggle" aria-pressed={paused} onClick={() => setPaused(value => !value)}>{paused ? 'Animar portada' : 'Pausar animación'}</button> : null}
  </div>
}
