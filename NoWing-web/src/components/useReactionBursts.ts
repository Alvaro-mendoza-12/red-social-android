import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
export type ReactionParticle = { id: string; emoji: string; userId?: string }
export const REACTION_FLIGHT_MS = 5_800
export function useReactionBursts(scope: string) {
  const [state, setState] = useState<{ scope: string; items: ReactionParticle[] }>({ scope, items: [] })
  const timers = useRef(new Map<string, number>()), seen = useRef(new Set<string>())
  useEffect(() => {
    const scheduled = timers.current, known = seen.current
    known.clear()
    return () => { scheduled.forEach(id => clearTimeout(id)); scheduled.clear() }
  }, [scope])
  const add = useCallback((item: ReactionParticle) => {
    if (!item.id || !item.emoji || item.emoji.length > 20 || seen.current.has(item.id)) return
    seen.current.add(item.id); if (seen.current.size > 256) seen.current.delete(seen.current.values().next().value!)
    const normalizedUser = item.userId?.trim().toLowerCase() || ''
    setState(current => {
      const existing = current.scope === scope ? current.items : []
      // Enforce at most 1 visible reaction per participant so rapid bursts never stack or overlap.
      const filtered = normalizedUser
        ? existing.filter(value => {
            const matches = value.userId?.trim().toLowerCase() === normalizedUser
            if (matches) {
              const oldTimer = timers.current.get(value.id)
              if (oldTimer !== undefined) {
                clearTimeout(oldTimer)
                timers.current.delete(value.id)
              }
            }
            return !matches
          })
        : existing
      return { scope, items: [...filtered, item].slice(-20) }
    })
    const timer = window.setTimeout(() => {
      setState(current => current.scope === scope ? { scope, items: current.items.filter(value => value.id !== item.id) } : current)
      timers.current.delete(item.id)
    }, REACTION_FLIGHT_MS + 150)
    timers.current.set(item.id, timer)
  }, [scope])
  return useMemo(() => ({ items: state.scope === scope ? state.items : [], add }), [state, scope, add])
}
