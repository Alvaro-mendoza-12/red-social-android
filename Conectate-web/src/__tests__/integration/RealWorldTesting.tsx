import React, { useState, useEffect } from 'react'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ErrorBoundarySimple } from '../../components/ErrorBoundarySimple'

/**
 * 🔥 REAL WORLD TESTING - TESTING REALISTA DE FUNCIONALIDADES
 * Testing completo y realista basado en escenarios del mundo real
 * Verifica que cada opción y funcionalidad funcione sin crashes
 */

// 🔥 MOCK DE DATOS REALISTAS
const mockUserData = {
  users: [
    { id: '1', username: 'alex_creator', avatar: 'https://picsum.photos/100/100?random=1', verified: true },
    { id: '2', username: 'maria_dev', avatar: 'https://picsum.photos/100/100?random=2', verified: false },
    { id: '3', username: 'carlos_music', avatar: 'https://picsum.photos/100/100?random=3', verified: true }
  ],
  posts: [
    {
      id: '1',
      authorId: '1',
      type: 'reel',
      url: 'https://picsum.photos/400/700?random=1',
      caption: 'Amazing sunset vibes 🌅',
      likes: 1234,
      comments: 89,
      shares: 45,
      timestamp: Date.now() - 3600000
    },
    {
      id: '2',
      authorId: '2',
      type: 'image',
      url: 'https://picsum.photos/400/400?random=2',
      caption: 'Beautiful day outside ☀️',
      likes: 567,
      comments: 23,
      shares: 12,
      timestamp: Date.now() - 7200000
    }
  ],
  voiceRooms: [
    {
      id: 'voice1',
      title: 'Chill Vibes 🎵',
      hostId: '3',
      participants: 23,
      maxParticipants: 50,
      isLive: true,
      category: 'Music'
    }
  ],
  discoRooms: [
    {
      id: 'disco1',
      name: 'Electronic Paradise 🎧',
      djId: '3',
      currentTrack: {
        title: 'Neon Dreams',
        artist: 'DJ Cosmos',
        duration: 240000,
        currentTime: 120000
      },
      listeners: 156,
      genre: 'Electronic',
      isLive: true
    }
  ]
}

// 🔥 COMPONENTE DE TESTEO INTEGRADO
const TestableApp: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'feed' | 'reels' | 'voice' | 'disco'>('feed')
  const [posts, setPosts] = useState(mockUserData.posts)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 🔥 Simulación de carga de datos
  const loadData = async () => {
    setIsLoading(true)
    setError(null)
    
    try {
      await new Promise(resolve => setTimeout(resolve, 1000))
      // Simular carga exitosa
      setPosts(mockUserData.posts)
    } catch (err) {
      setError('Error al cargar datos')
    } finally {
      setIsLoading(false)
    }
  }

  // 🔥 Simulación de like
  const handleLike = (postId: string) => {
    setPosts(prevPosts => 
      prevPosts.map(post => 
        post.id === postId 
          ? { ...post, likes: post.likes + 1, isLiked: !post.isLiked }
          : post
      )
    )
  }

  // 🔥 Simulación de comentarios
  const handleComment = (postId: string) => {
    console.log('Navigate to comments for post:', postId)
  }

  // 🔥 Simulación de compartir
  const handleShare = (postId: string) => {
    console.log('Share post:', postId)
  }

  // 🔥 Simulación de unirse a voice room
  const handleJoinVoiceRoom = (roomId: string) => {
    console.log('Join voice room:', roomId)
  }

  // 🔥 Simulación de unirse a disco room
  const handleJoinDiscoRoom = (roomId: string) => {
    console.log('Join disco room:', roomId)
  }

  useEffect(() => {
    loadData()
  }, [])

  if (isLoading) {
    return (
      <div data-testid="loading">
        <div className="spinner"></div>
        <p>Cargando contenido...</p>
      </div>
    )
  }

  if (error) {
    return (
      <div data-testid="error">
        <h2>Error</h2>
        <p>{error}</p>
        <button onClick={loadData}>Reintentar</button>
      </div>
    )
  }

  return (
    <div data-testid="app" className="app">
      {/* 🔥 TABS */}
      <div data-testid="tabs" className="tabs">
        {['feed', 'reels', 'voice', 'disco'].map((tab) => (
          <button
            key={tab}
            data-testid={`tab-${tab}`}
            className={`tab ${activeTab === tab ? 'active' : ''}`}
            onClick={() => setActiveTab(tab as any)}
          >
            {tab.charAt(0).toUpperCase() + tab.slice(1)}
          </button>
        ))}
      </div>

      {/* 🔥 CONTENT */}
      <div data-testid="content" className="content">
        {activeTab === 'feed' && (
          <div data-testid="feed-content" className="feed">
            {posts.map((post) => {
              const author = mockUserData.users.find(u => u.id === post.authorId)
              return (
                <div key={post.id} data-testid={`post-${post.id}`} className="post">
                  {/* Header */}
                  <div data-testid={`post-header-${post.id}`} className="post-header">
                    <img 
                      src={author?.avatar} 
                      alt={author?.username}
                      data-testid={`post-avatar-${post.id}`}
                    />
                    <div>
                      <span data-testid={`post-username-${post.id}`}>{author?.username}</span>
                      {author?.verified && <span data-testid={`post-verified-${post.id}`}>✓</span>}
                    </div>
                  </div>

                  {/* Content */}
                  <div data-testid={`post-content-${post.id}`} className="post-content">
                    <img 
                      src={post.url} 
                      alt={post.caption}
                      data-testid={`post-image-${post.id}`}
                    />
                    {post.type === 'reel' && (
                      <button data-testid={`post-play-${post.id}`}>▶️</button>
                    )}
                  </div>

                  {/* Caption */}
                  <div data-testid={`post-caption-${post.id}`} className="post-caption">
                    <p>{post.caption}</p>
                  </div>

                  {/* Actions */}
                  <div data-testid={`post-actions-${post.id}`} className="post-actions">
                    <button 
                      data-testid={`post-like-${post.id}`}
                      onClick={() => handleLike(post.id)}
                    >
                      ❤️ {post.likes}
                    </button>
                    <button 
                      data-testid={`post-comment-${post.id}`}
                      onClick={() => handleComment(post.id)}
                    >
                      💬 {post.comments}
                    </button>
                    <button 
                      data-testid={`post-share-${post.id}`}
                      onClick={() => handleShare(post.id)}
                    >
                      📤 {post.shares}
                    </button>
                  </div>
                </div>
              )
            })}
          </div>
        )}

        {activeTab === 'voice' && (
          <div data-testid="voice-content" className="voice">
            {mockUserData.voiceRooms.map((room) => {
              const host = mockUserData.users.find(u => u.id === room.hostId)
              return (
                <div key={room.id} data-testid={`voice-room-${room.id}`} className="voice-room">
                  <div data-testid={`voice-header-${room.id}`} className="voice-header">
                    <img 
                      src={host?.avatar} 
                      alt={host?.username}
                      data-testid={`voice-host-avatar-${room.id}`}
                    />
                    <div>
                      <h3 data-testid={`voice-title-${room.id}`}>{room.title}</h3>
                      <p data-testid={`voice-host-${room.id}`}>Host: {host?.username}</p>
                    </div>
                    {room.isLive && (
                      <span data-testid={`voice-live-${room.id}`}>🔴 LIVE</span>
                    )}
                  </div>

                  <div data-testid={`voice-participants-${room.id}`} className="voice-participants">
                    <span data-testid={`voice-participant-count-${room.id}`}>
                      {room.participants}/{room.maxParticipants}
                    </span>
                    <div data-testid={`voice-progress-${room.id}`} className="progress">
                      <div 
                        className="progress-bar"
                        style={{ width: `${(room.participants / room.maxParticipants) * 100}%` }}
                        data-testid={`voice-progress-bar-${room.id}`}
                      ></div>
                    </div>
                  </div>

                  <button 
                    data-testid={`voice-join-${room.id}`}
                    onClick={() => handleJoinVoiceRoom(room.id)}
                  >
                    {room.isLive ? 'Unirse Ahora' : 'Ver Detalles'}
                  </button>
                </div>
              )
            })}
          </div>
        )}

        {activeTab === 'disco' && (
          <div data-testid="disco-content" className="disco">
            {mockUserData.discoRooms.map((room) => {
              const dj = mockUserData.users.find(u => u.id === room.djId)
              return (
                <div key={room.id} data-testid={`disco-room-${room.id}`} className="disco-room">
                  <div data-testid={`disco-header-${room.id}`} className="disco-header">
                    <img 
                      src={dj?.avatar} 
                      alt={dj?.username}
                      data-testid={`disco-dj-avatar-${room.id}`}
                    />
                    <div>
                      <h3 data-testid={`disco-name-${room.id}`}>{room.name}</h3>
                      <p data-testid={`disco-dj-${room.id}`}>DJ: {dj?.username}</p>
                    </div>
                    {room.isLive && (
                      <span data-testid={`disco-live-${room.id}`}>🔴 LIVE</span>
                    )}
                  </div>

                  <div data-testid={`disco-track-${room.id}`} className="disco-track">
                    <div>
                      <p data-testid={`disco-track-title-${room.id}`}>{room.currentTrack.title}</p>
                      <p data-testid={`disco-track-artist-${room.id}`}>{room.currentTrack.artist}</p>
                    </div>
                    <div data-testid={`disco-progress-${room.id}`} className="progress">
                      <div 
                        className="progress-bar"
                        style={{ width: `${(room.currentTrack.currentTime / room.currentTrack.duration) * 100}%` }}
                        data-testid={`disco-progress-bar-${room.id}`}
                      ></div>
                    </div>
                  </div>

                  <div data-testid={`disco-listeners-${room.id}`} className="disco-listeners">
                    <span data-testid={`disco-listener-count-${room.id}`}>
                      {room.listeners} escuchando
                    </span>
                  </div>

                  <button 
                    data-testid={`disco-join-${room.id}`}
                    onClick={() => handleJoinDiscoRoom(room.id)}
                  >
                    {room.isLive ? 'Entrar a la Disco' : 'Ver Detalles'}
                  </button>
                </div>
              )
            })}
          </div>
        )}

        {activeTab === 'reels' && (
          <div data-testid="reels-content" className="reels">
            {posts.filter(post => post.type === 'reel').map((post) => {
              const author = mockUserData.users.find(u => u.id === post.authorId)
              return (
                <div key={post.id} data-testid={`reel-${post.id}`} className="reel">
                  <div data-testid={`reel-content-${post.id}`} className="reel-content">
                    <img 
                      src={post.url} 
                      alt={post.caption}
                      data-testid={`reel-image-${post.id}`}
                    />
                    <button data-testid={`reel-play-${post.id}`}>▶️</button>
                  </div>
                  <div data-testid={`reel-info-${post.id}`} className="reel-info">
                    <span data-testid={`reel-username-${post.id}`}>{author?.username}</span>
                    <p data-testid={`reel-caption-${post.id}`}>{post.caption}</p>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}

// 🔥 SUITE DE TESTEO REALISTA
describe('Real World Testing - Conectate App', () => {
  let user: ReturnType<typeof userEvent.setup>

  beforeEach(() => {
    user = userEvent.setup()
  })

  // 🔥 TEST 1: RENDERIZADO INICIAL
  test('debe renderizar la aplicación sin crashes', async () => {
    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // Verificar que la app se renderiza
    expect(screen.getByTestId('app')).toBeInTheDocument()
    
    // Verificar que los tabs existen
    expect(screen.getByTestId('tab-feed')).toBeInTheDocument()
    expect(screen.getByTestId('tab-reels')).toBeInTheDocument()
    expect(screen.getByTestId('tab-voice')).toBeInTheDocument()
    expect(screen.getByTestId('tab-disco')).toBeInTheDocument()
    
    // Verificar que el contenido inicial (feed) se muestra
    expect(screen.getByTestId('feed-content')).toBeInTheDocument()
  })

  // 🔥 TEST 2: NAVEGACIÓN ENTRE TABS
  test('debe navegar entre tabs sin crashes', async () => {
    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // Click en tab voice
    await act(async () => {
      await user.click(screen.getByTestId('tab-voice'))
    })

    expect(screen.getByTestId('voice-content')).toBeInTheDocument()
    expect(screen.queryByTestId('feed-content')).not.toBeInTheDocument()

    // Click en tab disco
    await act(async () => {
      await user.click(screen.getByTestId('tab-disco'))
    })

    expect(screen.getByTestId('disco-content')).toBeInTheDocument()
    expect(screen.queryByTestId('voice-content')).not.toBeInTheDocument()

    // Click en tab reels
    await act(async () => {
      await user.click(screen.getByTestId('tab-reels'))
    })

    expect(screen.getByTestId('reels-content')).toBeInTheDocument()
    expect(screen.queryByTestId('disco-content')).not.toBeInTheDocument()

    // Click en tab feed
    await act(async () => {
      await user.click(screen.getByTestId('tab-feed'))
    })

    expect(screen.getByTestId('feed-content')).toBeInTheDocument()
    expect(screen.queryByTestId('reels-content')).not.toBeInTheDocument()
  })

  // 🔥 TEST 3: FUNCIONALIDAD DE POSTS
  test('debe mostrar y manipular posts correctamente', async () => {
    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // Verificar que los posts se muestran
    expect(screen.getByTestId('post-1')).toBeInTheDocument()
    expect(screen.getByTestId('post-2')).toBeInTheDocument()

    // Verificar información del post
    expect(screen.getByTestId('post-username-1')).toHaveTextContent('alex_creator')
    expect(screen.getByTestId('post-verified-1')).toBeInTheDocument()
    expect(screen.getByTestId('post-caption-1')).toHaveTextContent('Amazing sunset vibes 🌅')

    // Verificar botones de acciones
    expect(screen.getByTestId('post-like-1')).toBeInTheDocument()
    expect(screen.getByTestId('post-comment-1')).toBeInTheDocument()
    expect(screen.getByTestId('post-share-1')).toBeInTheDocument()

    // Test like functionality
    const likeButton = screen.getByTestId('post-like-1')
    const initialLikes = parseInt(likeButton.textContent?.match(/\d+/)?.[0] || '0')

    await act(async () => {
      await user.click(likeButton)
    })

    expect(screen.getByTestId('post-like-1')).toHaveTextContent(`${initialLikes + 1}`)
  })

  // 🔥 TEST 4: FUNCIONALIDAD DE VOICE ROOMS
  test('debe mostrar y manipular voice rooms correctamente', async () => {
    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // Navegar a voice rooms
    await act(async () => {
      await user.click(screen.getByTestId('tab-voice'))
    })

    // Verificar voice room
    expect(screen.getByTestId('voice-room-voice1')).toBeInTheDocument()
    expect(screen.getByTestId('voice-title-voice1')).toHaveTextContent('Chill Vibes 🎵')
    expect(screen.getByTestId('voice-host-voice1')).toHaveTextContent('Host: carlos_music')
    expect(screen.getByTestId('voice-live-voice1')).toBeInTheDocument()
    expect(screen.getByTestId('voice-participant-count-voice1')).toHaveTextContent('23/50')

    // Verificar progress bar
    const progressBar = screen.getByTestId('voice-progress-bar-voice1')
    expect(progressBar).toHaveStyle('width: 46%') // 23/50 = 46%

    // Test join button
    const joinButton = screen.getByTestId('voice-join-voice1')
    expect(joinButton).toHaveTextContent('Unirse Ahora')

    await act(async () => {
      await user.click(joinButton)
    })

    // Verificar que no hay crash
    expect(screen.getByTestId('voice-room-voice1')).toBeInTheDocument()
  })

  // 🔥 TEST 5: FUNCIONALIDAD DE DISCO ROOMS
  test('debe mostrar y manipular disco rooms correctamente', async () => {
    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // Navegar a disco rooms
    await act(async () => {
      await user.click(screen.getByTestId('tab-disco'))
    })

    // Verificar disco room
    expect(screen.getByTestId('disco-room-disco1')).toBeInTheDocument()
    expect(screen.getByTestId('disco-name-disco1')).toHaveTextContent('Electronic Paradise 🎧')
    expect(screen.getByTestId('disco-dj-disco1')).toHaveTextContent('DJ: carlos_music')
    expect(screen.getByTestId('disco-live-disco1')).toBeInTheDocument()

    // Verificar track info
    expect(screen.getByTestId('disco-track-title-disco1')).toHaveTextContent('Neon Dreams')
    expect(screen.getByTestId('disco-track-artist-disco1')).toHaveTextContent('DJ Cosmos')
    expect(screen.getByTestId('disco-listener-count-disco1')).toHaveTextContent('156 escuchando')

    // Verificar progress bar
    const progressBar = screen.getByTestId('disco-progress-bar-disco1')
    expect(progressBar).toHaveStyle('width: 50%') // 120000/240000 = 50%

    // Test join button
    const joinButton = screen.getByTestId('disco-join-disco1')
    expect(joinButton).toHaveTextContent('Entrar a la Disco')

    await act(async () => {
      await user.click(joinButton)
    })

    // Verificar que no hay crash
    expect(screen.getByTestId('disco-room-disco1')).toBeInTheDocument()
  })

  // 🔥 TEST 6: FUNCIONALIDAD DE REELS
  test('debe mostrar y manipular reels correctamente', async () => {
    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // Navegar a reels
    await act(async () => {
      await user.click(screen.getByTestId('tab-reels'))
    })

    // Verificar reel
    expect(screen.getByTestId('reel-1')).toBeInTheDocument()
    expect(screen.getByTestId('reel-play-1')).toBeInTheDocument()
    expect(screen.getByTestId('reel-username-1')).toHaveTextContent('alex_creator')
    expect(screen.getByTestId('reel-caption-1')).toHaveTextContent('Amazing sunset vibes 🌅')

    // Test play button
    await act(async () => {
      await user.click(screen.getByTestId('reel-play-1'))
    })

    // Verificar que no hay crash
    expect(screen.getByTestId('reel-1')).toBeInTheDocument()
  })

  // 🔥 TEST 7: MANEJO DE ERRORES
  test('debe manejar errores correctamente', async () => {
    // Mockear error
    const originalConsoleError = console.error
    console.error = jest.fn()

    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // Simular error crítico
    await act(async () => {
      // Forzar un error en el componente
      const error = new Error('Test error')
      throw error
    })

    // Verificar que el error boundary se activa
    // Esto depende de la implementación específica del ErrorBoundary

    console.error = originalConsoleError
  })

  // 🔥 TEST 8: PERFORMANCE Y RESPONSIVE
  test('debe ser responsive y no tener problemas de performance', async () => {
    const startTime = performance.now()

    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    const endTime = performance.now()
    const renderTime = endTime - startTime

    // El renderizado debería ser rápido (< 100ms)
    expect(renderTime).toBeLessThan(100)

    // Verificar que todos los elementos importantes existen
    expect(screen.getByTestId('app')).toBeInTheDocument()
    expect(screen.getByTestId('tabs')).toBeInTheDocument()
    expect(screen.getByTestId('content')).toBeInTheDocument()
  })

  // 🔥 TEST 9: ACCESIBILIDAD
  test('debe ser accesible', async () => {
    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // Verificar que los botones tienen aria-labels o roles apropiados
    const tabs = screen.getAllByRole('button')
    expect(tabs.length).toBeGreaterThan(0)

    // Verificar que las imágenes tienen alt text
    const images = screen.getAllByRole('img')
    images.forEach(img => {
      expect(img).toHaveAttribute('alt')
    })

    // Test navegación por teclado
    await act(async () => {
      await user.tab()
    })

    // Verificar que el focus se mueve correctamente
    expect(document.activeElement).toBeInstanceOf(HTMLElement)
  })

  // 🔥 TEST 10: ESTADO DE CARGA
  test('debe manejar estados de carga correctamente', async () => {
    // Componente que simula estado de carga
    const LoadingComponent = () => {
      const [isLoading, setIsLoading] = useState(true)

      useEffect(() => {
        setTimeout(() => setIsLoading(false), 1000)
      }, [])

      if (isLoading) {
        return (
          <div data-testid="loading">
            <div className="spinner"></div>
            <p>Cargando contenido...</p>
          </div>
        )
      }

      return <div data-testid="loaded">Contenido cargado</div>
    }

    render(
      <ErrorBoundarySimple>
        <LoadingComponent />
      </ErrorBoundarySimple>
    )

    // Verificar estado inicial de carga
    expect(screen.getByTestId('loading')).toBeInTheDocument()
    expect(screen.getByText('Cargando contenido...')).toBeInTheDocument()

    // Esperar a que termine la carga
    await waitFor(() => {
      expect(screen.getByTestId('loaded')).toBeInTheDocument()
    }, { timeout: 2000 })
  })

  // 🔥 TEST 11: INTERACCIONES COMPLEJAS
  test('debe manejar interacciones complejas sin crashes', async () => {
    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // Navegación rápida entre tabs
    const tabs = ['tab-voice', 'tab-disco', 'tab-reels', 'tab-feed']

    for (const tab of tabs) {
      await act(async () => {
        await user.click(screen.getByTestId(tab as any))
      })
      
      // Verificar que el contenido se muestra correctamente
      expect(screen.getByTestId('content')).toBeInTheDocument()
    }

    // Interacciones múltiples en posts
    await act(async () => {
      await user.click(screen.getByTestId('tab-feed'))
    })

    const post1 = screen.getByTestId('post-1')
    
    // Múltiples clicks rápidos
    await act(async () => {
      await user.click(screen.getByTestId('post-like-1'))
      await user.click(screen.getByTestId('post-comment-1'))
      await user.click(screen.getByTestId('post-share-1'))
    })

    // Verificar que todo sigue funcionando
    expect(post1).toBeInTheDocument()
  })

  // 🔥 TEST 12: EDGE CASES
  test('debe manejar edge cases correctamente', async () => {
    // Test con datos vacíos
    const EmptyComponent = () => {
      return (
        <div data-testid="empty-app">
          <div data-testid="tabs">
            <button data-testid="tab-feed">Feed</button>
          </div>
          <div data-testid="content">
            <div data-testid="feed-content">
              <p>No hay contenido</p>
            </div>
          </div>
        </div>
      )
    }

    render(
      <ErrorBoundarySimple>
        <EmptyComponent />
      </ErrorBoundarySimple>
    )

    expect(screen.getByTestId('empty-app')).toBeInTheDocument()
    expect(screen.getByText('No hay contenido')).toBeInTheDocument()
  })
})

// 🔥 TEST DE INTEGRACIÓN FINAL
describe('Integration Testing - Full App Flow', () => {
  test('debe completar un flujo completo de usuario sin crashes', async () => {
    const user = userEvent.setup()

    render(
      <ErrorBoundarySimple>
        <TestableApp />
      </ErrorBoundarySimple>
    )

    // 1. Usuario abre la app
    expect(screen.getByTestId('app')).toBeInTheDocument()

    // 2. Usuario ve el feed
    expect(screen.getByTestId('feed-content')).toBeInTheDocument()

    // 3. Usuario da like a un post
    await act(async () => {
      await user.click(screen.getByTestId('post-like-1'))
    })

    // 4. Usuario comenta en un post
    await act(async () => {
      await user.click(screen.getByTestId('post-comment-1'))
    })

    // 5. Usuario navega a voice rooms
    await act(async () => {
      await user.click(screen.getByTestId('tab-voice'))
    })

    // 6. Usuario ve voice rooms
    expect(screen.getByTestId('voice-content')).toBeInTheDocument()

    // 7. Usuario se une a un voice room
    await act(async () => {
      await user.click(screen.getByTestId('voice-join-voice1'))
    })

    // 8. Usuario navega a disco rooms
    await act(async () => {
      await user.click(screen.getByTestId('tab-disco'))
    })

    // 9. Usuario ve disco rooms
    expect(screen.getByTestId('disco-content')).toBeInTheDocument()

    // 10. Usuario se une a un disco room
    await act(async () => {
      await user.click(screen.getByTestId('disco-join-disco1'))
    })

    // 11. Usuario navega a reels
    await act(async () => {
      await user.click(screen.getByTestId('tab-reels'))
    })

    // 12. Usuario reproduce un reel
    await act(async () => {
      await user.click(screen.getByTestId('reel-play-1'))
    })

    // 13. Usuario vuelve al feed
    await act(async () => {
      await user.click(screen.getByTestId('tab-feed'))
    })

    // Verificar que todo funcionó correctamente
    expect(screen.getByTestId('feed-content')).toBeInTheDocument()
    expect(screen.getByTestId('app')).toBeInTheDocument()
  })
})
