import { useCallback, useEffect, useRef, useState } from 'react'
import { narrationFor, type NovvinProblem, type ErrorCharacter } from './errorCatalog'

const voiceTraits: Record<ErrorCharacter, { pitch: number; rate: number }> = {
  confused: { pitch: 1.13, rate: 0.94 },
  guardian: { pitch: 0.93, rate: 0.92 },
  protector: { pitch: 0.98, rate: 1.01 },
  detective: { pitch: 1.14, rate: 1.03 },
  overloaded: { pitch: 0.86, rate: 1.08 },
  sleeper: { pitch: 0.95, rate: 0.79 },
}

let activePresentation: { utterance: SpeechSynthesisUtterance; stop: () => void } | undefined

export function useErrorSpeech(problem: NovvinProblem) {
  const text = narrationFor(problem)
  const current = useRef<SpeechSynthesisUtterance | null>(null)
  const [speaking, setSpeaking] = useState(false)
  const [unavailableText, setUnavailableText] = useState<string | null>(null)

  const stop = useCallback(() => {
    const utterance = current.current
    if (utterance) {
      current.current = null
      if (activePresentation?.utterance === utterance) activePresentation = undefined
      window.speechSynthesis?.cancel()
    }
    setSpeaking(false)
  }, [])

  useEffect(() => {
    const onVisibility = () => { if (document.hidden) stop() }
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pagehide', stop)
    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pagehide', stop)
      stop()
    }
  }, [stop, text])

  function listen() {
    const engine = window.speechSynthesis
    if (!engine || typeof window.SpeechSynthesisUtterance !== 'function') {
      setUnavailableText(text)
      return
    }
    activePresentation?.stop()
    setUnavailableText(null)

    try {
      const choices = engine.getVoices()
      const spanish = choices.filter(voice => /^es(?:[-_]|$)/i.test(voice.lang))
      const voice = spanish.find(item => item.localService && item.lang.toLowerCase() === 'es-mx')
        ?? spanish.find(item => item.localService) ?? spanish[0]
      if (!voice && choices.length > 0) {
        setUnavailableText(text)
        return
      }
      const utterance = new SpeechSynthesisUtterance(text)
      utterance.lang = voice?.lang ?? 'es'
      if (voice) utterance.voice = voice
      Object.assign(utterance, voiceTraits[problem.character])

      const finish = (failed: boolean) => {
        if (current.current !== utterance) return
        current.current = null
        if (activePresentation?.utterance === utterance) activePresentation = undefined
        setSpeaking(false)
        setUnavailableText(failed ? text : null)
      }
      utterance.onend = () => finish(false)
      utterance.onerror = () => finish(true)
      current.current = utterance
      activePresentation = { utterance, stop }
      setSpeaking(true)
      engine.speak(utterance)
    } catch {
      stop()
      setUnavailableText(text)
    }
  }

  return { listen, stop, speaking, unavailable: unavailableText === text }
}
