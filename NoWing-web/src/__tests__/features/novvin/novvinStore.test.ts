import {
  appendNovvinMessage,
  createNovvinConversation,
  createNovvinMessage,
  loadNovvinConversations,
  normalizeNovvinConversations,
  saveNovvinConversations,
} from '../../../features/novvin/novvinStore'

describe('Novvin browser history', () => {
  beforeEach(() => window.localStorage.clear())

  test('keeps conversations isolated by account', () => {
    const first = appendNovvinMessage(
      createNovvinConversation(),
      createNovvinMessage('user', 'mensaje de la primera cuenta'),
    )
    saveNovvinConversations('account-a', [first])

    expect(loadNovvinConversations('account-a')[0]?.messages.at(-1)?.text).toBe(
      'mensaje de la primera cuenta',
    )
    expect(loadNovvinConversations('account-b')).toEqual([])
  })

  test('does not accept malformed roles or empty messages from storage', () => {
    const normalized = normalizeNovvinConversations([
      {
        id: 'conversation-1',
        title: 'Prueba',
        createdAt: 1,
        updatedAt: 2,
        messages: [
          { id: 'bad', role: 'system', text: 'no debe entrar', createdAt: 1 },
          { id: 'empty', role: 'user', text: '   ', createdAt: 1 },
          { id: 'good', role: 'assistant', text: 'respuesta segura', createdAt: 2 },
        ],
      },
    ])

    expect(normalized).toHaveLength(1)
    expect(normalized[0]?.messages).toHaveLength(1)
    expect(normalized[0]?.messages[0]?.text).toBe('respuesta segura')
  })

  test('caps stored conversations and messages', () => {
    const conversations = Array.from({ length: 45 }, (_, index) => ({
      ...createNovvinConversation(),
      id: `conversation-${index}`,
      updatedAt: index + 1,
      messages: Array.from({ length: 110 }, (__, messageIndex) =>
        createNovvinMessage('user', `message-${messageIndex}`),
      ),
    }))

    const normalized = normalizeNovvinConversations(conversations)
    expect(normalized).toHaveLength(40)
    expect(normalized[0]?.id).toBe('conversation-44')
    expect(normalized[0]?.messages).toHaveLength(100)
  })
})
