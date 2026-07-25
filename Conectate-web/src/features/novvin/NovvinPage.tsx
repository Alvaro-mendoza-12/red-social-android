import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, Bot, History, Plus, RefreshCw, Send, Trash2, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import {
  askNovvin,
  friendlyNovvinError,
  getNovvinHealth,
  type NovvinHealth,
} from './novvinClient'
import {
  appendNovvinMessage,
  createNovvinConversation,
  createNovvinMessage,
  loadNovvinConversations,
  saveNovvinConversations,
  type NovvinConversation,
} from './novvinStore'
import './novvin.css'

type ChatState = {
  ownerId: string
  currentId: string
  conversations: NovvinConversation[]
}

type FailedRequest = {
  conversationId: string
  message: string
} | null

function loadOwnerState(ownerId: string): ChatState {
  const stored = loadNovvinConversations(ownerId)
  const conversations = stored.length ? stored : [createNovvinConversation()]
  return { ownerId, currentId: conversations[0].id, conversations }
}

function withUpdatedConversation(
  state: ChatState,
  conversationId: string,
  transform: (conversation: NovvinConversation) => NovvinConversation,
): ChatState {
  return {
    ...state,
    conversations: state.conversations
      .map((conversation) =>
        conversation.id === conversationId ? transform(conversation) : conversation,
      )
      .sort((left, right) => right.updatedAt - left.updatedAt),
  }
}

function conversationHistory(conversation: NovvinConversation): string {
  return conversation.messages
    .slice(-10)
    .map((message) => `${message.role === 'assistant' ? 'Novvin' : 'Usuario'}: ${message.text}`)
    .join('\n')
    .slice(-5_000)
}

export function NovvinPage() {
  const { user, isFallbackSession } = useAuth()
  const ownerId = user?.id?.trim() || ''
  const [chatState, setChatState] = useState<ChatState>(() => loadOwnerState(ownerId))
  const [draft, setDraft] = useState('')
  const [expandedAnswers, setExpandedAnswers] = useState(false)
  const [pendingConversationId, setPendingConversationId] = useState<string | null>(null)
  const [failedRequest, setFailedRequest] = useState<FailedRequest>(null)
  const [error, setError] = useState<string | null>(null)
  const [health, setHealth] = useState<NovvinHealth | null>(null)
  const [healthError, setHealthError] = useState<string | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const requestController = useRef<AbortController | null>(null)
  const endOfMessagesRef = useRef<HTMLDivElement | null>(null)
  const historyPanelRef = useRef<HTMLElement | null>(null)

  const activeConversation =
    chatState.conversations.find((conversation) => conversation.id === chatState.currentId) ||
    chatState.conversations[0]

  useEffect(() => {
    if (chatState.ownerId === ownerId) return
    requestController.current?.abort()
    setPendingConversationId(null)
    setFailedRequest(null)
    setError(null)
    setChatState(loadOwnerState(ownerId))
  }, [chatState.ownerId, ownerId])

  useEffect(() => {
    if (!ownerId || chatState.ownerId !== ownerId) return
    saveNovvinConversations(ownerId, chatState.conversations)
  }, [chatState, ownerId])

  useEffect(() => {
    endOfMessagesRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
  }, [activeConversation?.messages.length, pendingConversationId])

  useEffect(() => {
    if (!ownerId || !isFallbackSession) return
    const controller = new AbortController()
    setHealthError(null)
    void getNovvinHealth(controller.signal)
      .then(setHealth)
      .catch((caught) => {
        if (!controller.signal.aborted) setHealthError(friendlyNovvinError(caught))
      })
    return () => controller.abort()
  }, [isFallbackSession, ownerId])

  useEffect(() => () => requestController.current?.abort(), [])

  useEffect(() => {
    const mobileNav = document.querySelector<HTMLElement>('.mobile-nav')
    if (!historyOpen) {
      mobileNav?.removeAttribute('inert')
      return
    }
    mobileNav?.setAttribute('inert', '')
    historyPanelRef.current?.focus()
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setHistoryOpen(false)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => {
      mobileNav?.removeAttribute('inert')
      window.removeEventListener('keydown', closeOnEscape)
    }
  }, [historyOpen])

  if (!isFallbackSession) {
    return (
      <StatusView
        title="Novvin necesita el servidor Ubuntu"
        description="Inicia sesión mediante el backend principal de Conectate para usar Novvin v5."
      />
    )
  }

  if (!activeConversation) {
    return <StatusView title="Preparando Novvin" description="Cargando tu historial local." />
  }

  async function requestAnswer(conversationId: string, message: string, appendUser: boolean) {
    if (pendingConversationId || requestController.current) return
    const cleanMessage = message.trim()
    if (!cleanMessage) return

    let target = chatState.conversations.find((conversation) => conversation.id === conversationId)
    if (!target) return
    if (appendUser) {
      const userMessage = createNovvinMessage('user', cleanMessage)
      target = appendNovvinMessage(target, userMessage)
      const nextTarget = target
      setChatState((current) =>
        withUpdatedConversation(current, conversationId, () => nextTarget),
      )
    }

    setDraft('')
    setError(null)
    setFailedRequest(null)
    setPendingConversationId(conversationId)
    const controller = new AbortController()
    requestController.current = controller

    try {
      const response = await askNovvin(
        cleanMessage,
        {
          conversationId,
          history: conversationHistory(target),
          screen: 'web_messages',
          locale: navigator.language || 'es',
          detailLevel: expandedAnswers ? 'expanded' : 'standard',
        },
        controller.signal,
      )
      const assistantMessage = createNovvinMessage(
        'assistant',
        response.answer,
        response.suggestions,
      )
      setChatState((current) =>
        withUpdatedConversation(current, conversationId, (conversation) =>
          appendNovvinMessage(conversation, assistantMessage),
        ),
      )
    } catch (caught) {
      if (!controller.signal.aborted) {
        setError(friendlyNovvinError(caught))
        setFailedRequest({ conversationId, message: cleanMessage })
      }
    } finally {
      if (requestController.current === controller) requestController.current = null
      setPendingConversationId((current) => (current === conversationId ? null : current))
    }
  }

  function startNewConversation() {
    const conversation = createNovvinConversation()
    requestController.current?.abort()
    setPendingConversationId(null)
    setFailedRequest(null)
    setError(null)
    setChatState((current) => ({
      ...current,
      currentId: conversation.id,
      conversations: [conversation, ...current.conversations].slice(0, 40),
    }))
    setHistoryOpen(false)
  }

  function deleteConversation(conversationId: string) {
    if (pendingConversationId === conversationId) {
      requestController.current?.abort()
      requestController.current = null
      setPendingConversationId(null)
    }
    setChatState((current) => {
      let remaining = current.conversations.filter(
        (conversation) => conversation.id !== conversationId,
      )
      if (!remaining.length) remaining = [createNovvinConversation()]
      return {
        ...current,
        currentId: current.currentId === conversationId ? remaining[0].id : current.currentId,
        conversations: remaining,
      }
    })
  }

  return (
    <section className="novvin-page" aria-label="Asistente Novvin">
      {historyOpen ? (
        <button
          className="novvin-history-backdrop"
          type="button"
          aria-label="Cerrar historial"
          onClick={() => setHistoryOpen(false)}
        />
      ) : null}
      <aside
        ref={historyPanelRef}
        className={historyOpen ? 'novvin-history-panel open' : 'novvin-history-panel'}
        role={historyOpen ? 'dialog' : undefined}
        aria-modal={historyOpen ? true : undefined}
        aria-label="Historial de Novvin"
        tabIndex={historyOpen ? -1 : undefined}
      >
        <div className="novvin-history-head">
          <div>
            <span>Historial local</span>
            <strong>Chats de Novvin</strong>
          </div>
          <button
            className="novvin-icon-button mobile-only"
            type="button"
            aria-label="Cerrar historial"
            onClick={() => setHistoryOpen(false)}
          >
            <X aria-hidden="true" />
          </button>
        </div>
        <button className="novvin-new-button" type="button" onClick={startNewConversation}>
          <Plus aria-hidden="true" />
          Nuevo chat
        </button>
        <div className="novvin-conversation-list">
          {chatState.conversations.map((conversation) => (
            <div
              className={
                conversation.id === chatState.currentId
                  ? 'novvin-conversation-row active'
                  : 'novvin-conversation-row'
              }
              key={conversation.id}
            >
              <button
                type="button"
                onClick={() => {
                  setChatState((current) => ({ ...current, currentId: conversation.id }))
                  setHistoryOpen(false)
                  setError(null)
                }}
              >
                <strong>{conversation.title}</strong>
                <span>{new Date(conversation.updatedAt).toLocaleString()}</span>
              </button>
              <button
                className="novvin-delete-button"
                type="button"
                aria-label={`Eliminar ${conversation.title}`}
                onClick={() => deleteConversation(conversation.id)}
              >
                <Trash2 aria-hidden="true" />
              </button>
            </div>
          ))}
        </div>
        <p className="novvin-local-note">
          El historial se guarda por cuenta en este navegador. No se usa como entrenamiento automático.
        </p>
      </aside>

      <div
        className="novvin-chat-panel"
        aria-hidden={historyOpen ? true : undefined}
        inert={historyOpen ? true : undefined}
      >
        <header className="novvin-chat-head">
          <div className="novvin-head-copy">
            <Link className="novvin-icon-button" to="/app/messages" aria-label="Volver a mensajes">
              <ArrowLeft aria-hidden="true" />
            </Link>
            <span className="novvin-avatar" aria-hidden="true"><Bot /></span>
            <div>
              <h2>Novvin</h2>
              <p>
                {health
                  ? `v5 local · dataset ${health.datasetVersion} · sin acceso a Internet`
                  : healthError || 'Comprobando versión local...'}
              </p>
            </div>
          </div>
          <button
            className="novvin-icon-button mobile-only"
            type="button"
            aria-label="Abrir historial"
            onClick={() => setHistoryOpen(true)}
          >
            <History aria-hidden="true" />
          </button>
        </header>

        <div className="novvin-message-list" aria-live="polite">
          {activeConversation.messages.map((message) => (
            <article
              className={message.role === 'assistant' ? 'novvin-message assistant' : 'novvin-message user'}
              key={message.id}
            >
              {message.role === 'assistant' ? <span className="novvin-mini-avatar"><Bot aria-hidden="true" /></span> : null}
              <div>
                <p>{message.text}</p>
                {message.role === 'assistant' && message.suggestions.length ? (
                  <div className="novvin-suggestions">
                    {message.suggestions.map((suggestion) => (
                      <button
                        type="button"
                        key={suggestion}
                        disabled={Boolean(pendingConversationId)}
                        onClick={() => void requestAnswer(activeConversation.id, suggestion, true)}
                      >
                        {suggestion}
                      </button>
                    ))}
                  </div>
                ) : null}
              </div>
            </article>
          ))}
          {pendingConversationId === activeConversation.id ? (
            <div className="novvin-thinking" role="status">
              <span /><span /><span />
              Novvin está preparando una respuesta verificada
            </div>
          ) : null}
          <div ref={endOfMessagesRef} />
        </div>

        <div className="novvin-composer-shell">
          {error ? (
            <div className="novvin-error" role="alert">
              <span>{error}</span>
              {failedRequest ? (
                <button
                  type="button"
                  onClick={() =>
                    void requestAnswer(failedRequest.conversationId, failedRequest.message, false)
                  }
                  disabled={Boolean(pendingConversationId)}
                >
                  <RefreshCw aria-hidden="true" />
                  Reintentar
                </button>
              ) : null}
            </div>
          ) : null}
          <form
            className="novvin-composer"
            onSubmit={(event) => {
              event.preventDefault()
              void requestAnswer(activeConversation.id, draft, true)
            }}
          >
            <textarea
              value={draft}
              maxLength={1_000}
              rows={1}
              placeholder="Escribe a Novvin"
              aria-label="Mensaje para Novvin"
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault()
                  void requestAnswer(activeConversation.id, draft, true)
                }
              }}
            />
            <button
              type="submit"
              aria-label="Enviar mensaje"
              disabled={!draft.trim() || Boolean(pendingConversationId)}
            >
              <Send aria-hidden="true" />
            </button>
          </form>
          <div className="novvin-composer-meta">
            <label>
              <input
                type="checkbox"
                checked={expandedAnswers}
                onChange={(event) => setExpandedAnswers(event.target.checked)}
              />
              Respuestas detalladas
            </label>
            <span>{draft.length}/1.000</span>
          </div>
        </div>
      </div>
    </section>
  )
}
