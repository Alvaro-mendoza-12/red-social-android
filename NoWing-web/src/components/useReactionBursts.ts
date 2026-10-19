import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
export type ReactionParticle = { id: string; emoji: string }
export function useReactionBursts(scope: string) {
  const [state, setState] = useState<{ scope: string; items: ReactionParticle[] }>({ scope, items: [] })
  const timers = useRef(new Set<number>()), seen = useRef(new Set<string>())
  useEffect(() => {
    const scheduled = timers.current, known = seen.current
    known.clear()
    return () => { scheduled.forEach(id => clearTimeout(id)); scheduled.clear() }
  }, [scope])
  const add = useCallback((item: ReactionParticle) => {
    if (!item.id || !item.emoji || item.emoji.length > 20 || seen.current.has(item.id)) return
    seen.current.add(item.id); if (seen.current.size > 256) seen.current.delete(seen.current.values().next().value!)
    setState(current => ({ scope, items: [...(current.scope === scope ? current.items : []), item].slice(-20) }))
    const timer = window.setTimeout(() => {
      setState(current => current.scope === scope ? { scope, items: current.items.filter(value => value.id !== item.id) } : current)
      timers.current.delete(timer)
    }, 3100)
    timers.current.add(timer)
  }, [scope])
  return useMemo(() => ({ items: state.scope === scope ? state.items : [], add }), [state, scope, add])
}
