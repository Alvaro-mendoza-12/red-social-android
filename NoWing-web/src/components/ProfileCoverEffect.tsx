import { useEffect, useRef, useState, type CSSProperties } from 'react'
import './profileCoverEffect.css'

export const PROFILE_EFFECT_CHOICES = [['none', 'Sin animación'], ['sparkles', 'Destellos'], ['constellation', 'Constelación'], ['confetti', 'Confeti'], ['waves', 'Ondas'], ['orbit', 'Órbita'], ['rain', 'Lluvia'], ['equalizer', 'Ritmo']] as const
const accents: Record<string, string> = { conectate: '#67e8dc', oceano: '#69cfff', jade: '#6ee7b7', solar: '#ffcd70', sakura: '#f9a8d4', cobalto: '#8db5ff', bosque: '#86c99c', aurora: '#a5b4fc', grafito: '#d4d4d8', rubi: '#fda4af', ciruela: '#d8b4fe', artico: '#a5f3fc', neon: '#c4ff7c', terracota: '#fdba9a', tinta: '#a5b4fc', laguna: '#67e8f9', citrico: '#fde68a', orquidea: '#e9a8f9', carmesi: '#fda4af', prisma: '#c4b5fd', melon: '#fdbb9b', acero: '#cbd5e1', selva: '#a7f3d0', noche: '#b3baff' }
export const PROFILE_THEME_CHOICES = Object.entries({ conectate: 'Novvin', oceano: 'Océano', jade: 'Jade', solar: 'Solar', sakura: 'Sakura', cobalto: 'Cobalto', bosque: 'Bosque', aurora: 'Aurora', grafito: 'Grafito', rubi: 'Rubí', ciruela: 'Ciruela', artico: 'Ártico', neon: 'Neón', terracota: 'Terracota', tinta: 'Tinta', laguna: 'Laguna', citrico: 'Cítrico', orquidea: 'Orquídea', carmesi: 'Carmesí', prisma: 'Prisma', melon: 'Melón', acero: 'Acero', selva: 'Selva', noche: 'Noche' }).map(([id, label]) => ({ id, label, color: accents[id] }))
export const profileThemeColor = (theme?: string) => accents[theme || 'conectate'] || accents.conectate
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
