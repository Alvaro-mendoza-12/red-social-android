import {
  NOVVIN_V5_SOURCE,
  friendlyNovvinError,
  validateNovvinChatResponse,
  validateNovvinHealth,
} from '../../../features/novvin/novvinContract'

describe('Novvin v5 web contract', () => {
  test('rejects an older or unknown response source', () => {
    expect(() =>
      validateNovvinChatResponse({
        answer: 'respuesta',
        intent: 'saludo',
        confidence: 1,
        suggestions: [],
        source: 'legacy-chatbot',
        learnedFromUsers: false,
      }),
    ).toThrow('AI_VERSION_MISMATCH')
  })

  test('normalizes a valid v5 response without claiming automatic learning', () => {
    const response = validateNovvinChatResponse({
      answer: '  respuesta revisada  ',
      intent: 'saludo',
      confidence: 1,
      suggestions: [' Uno ', '', 'Dos', 'Tres', 'Cuatro'],
      source: NOVVIN_V5_SOURCE,
      learnedFromUsers: false,
    })

    expect(response.answer).toBe('respuesta revisada')
    expect(response.suggestions).toEqual(['Uno', 'Dos', 'Tres'])
    expect(response.learnedFromUsers).toBe(false)
  })

  test('requires a ready v5 health response', () => {
    expect(
      validateNovvinHealth({
        ready: true,
        modelType: NOVVIN_V5_SOURCE,
        datasetVersion: 5,
        datasetFingerprint: 'fingerprint',
        guideVersion: 3,
        lastLoadAction: 'loaded',
      }).datasetVersion,
    ).toBe(5)
    expect(() =>
      validateNovvinHealth({
        ready: true,
        modelType: NOVVIN_V5_SOURCE,
        datasetVersion: 4,
        datasetFingerprint: 'old',
        guideVersion: 2,
        lastLoadAction: 'loaded',
      }),
    ).toThrow('AI_VERSION_MISMATCH')
  })

  test('maps infrastructure errors to user-facing messages', () => {
    expect(friendlyNovvinError(new Error('AI_SERVICE_TIMEOUT'))).toContain('tardó demasiado')
    expect(friendlyNovvinError(new Error('AI_SERVICE_UNAVAILABLE'))).toContain('no está disponible')
  })
})
