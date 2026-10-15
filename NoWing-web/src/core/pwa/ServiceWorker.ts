/**
 * 🔥 ENTERPRISE SERVICE WORKER - NIVEL INSTAGRAM/TIKTOK
 * Service Worker optimizado para PWA con caching inteligente
 */

const CACHE_NAME = 'conectate-web-v2.0.0'
const STATIC_CACHE = 'static-v2.0.0'
const DYNAMIC_CACHE = 'dynamic-v2.0.0'
const API_CACHE = 'api-v2.0.0'
const MEDIA_CACHE = 'media-v2.0.0'

// 🔥 ARCHIVOS ESENCIALES PARA CACHEAR
const STATIC_ASSETS = [
  '/',
  '/index.html',
  '/manifest.json',
  '/styles/enterprise.css',
  '/icons/icon-192x192.png',
  '/icons/icon-512x512.png',
  '/offline.html'
]

// 🔥 PATRONES DE CACHEO
const CACHE_PATTERNS = {
  static: /\.(js|css|png|jpg|jpeg|gif|webp|svg|woff|woff2)$/,
  api: /\/api\//,
  media: /\/media\//,
  dynamic: /.*/
}

// 🔥 INSTALACIÓN
self.addEventListener('install', (event) => {
  console.log('🔥 Service Worker installing...')
  
  event.waitUntil(
    caches.open(STATIC_CACHE)
      .then(cache => cache.addAll(STATIC_ASSETS))
      .then(() => {
        console.log('✅ Static assets cached successfully')
        self.skipWaiting()
      })
      .catch(error => {
        console.error('❌ Error caching static assets:', error)
      })
  )
})

// 🔥 ACTIVACIÓN
self.addEventListener('activate', (event) => {
  console.log('🔥 Service Worker activating...')
  
  event.waitUntil(
    caches.keys()
      .then(cacheNames => {
        return Promise.all(
          cacheNames
            .filter(name => name !== STATIC_CACHE && name !== DYNAMIC_CACHE && name !== API_CACHE && name !== MEDIA_CACHE)
            .map(name => caches.delete(name))
        )
      })
      .then(() => {
        console.log('✅ Old caches cleaned')
        return self.clients.claim()
      })
      .then(() => {
        console.log('✅ Service Worker activated')
      })
  )
})

// 🔥 FETCH - ESTRATEGIA INTELIGENTE DE CACHEO
self.addEventListener('fetch', (event) => {
  const request = event.request
  const url = new URL(request.url)
  
  // 🔥 IGNORAR CHROME EXTENSIONS
  if (url.protocol === 'chrome-extension:') {
    return
  }
  
  // 🔥 ESTRATEGIAS POR TIPO DE REQUEST
  if (request.method === 'GET') {
    if (CACHE_PATTERNS.static.test(url.pathname)) {
      // Cache First para assets estáticos
      event.respondWith(cacheFirst(request, STATIC_CACHE))
    } else if (CACHE_PATTERNS.api.test(url.pathname)) {
      // Network First para API calls
      event.respondWith(networkFirst(request, API_CACHE))
    } else if (CACHE_PATTERNS.media.test(url.pathname)) {
      // Cache First para media con actualización en background
      event.respondWith(cacheFirstWithBackgroundUpdate(request, MEDIA_CACHE))
    } else {
      // Stale While Revalidate para el resto
      event.respondWith(staleWhileRevalidate(request, DYNAMIC_CACHE))
    }
  } else if (request.method === 'POST' || request.method === 'PUT' || request.method === 'DELETE') {
    // Network Only para requests que modifican datos
    event.respondWith(networkOnly(request))
  }
})

// 🔥 ESTRATEGIAS DE CACHEO

// Cache First - Sirve desde cache, actualiza en background
async function cacheFirst(request: Request, cacheName: string): Promise<Response> {
  const cache = await caches.open(cacheName)
  const cached = await cache.match(request)
  
  if (cached) {
    // Actualizar en background
    updateCacheInBackground(request, cacheName)
    return cached
  }
  
  try {
    const response = await fetch(request)
    if (response.ok) {
      cache.put(request, response.clone())
    }
    return response
  } catch (error) {
    console.error('❌ Network request failed:', error)
    return new Response('Offline', { status: 503, statusText: 'Service Unavailable' })
  }
}

// Network First - Intenta red primero, luego cache
async function networkFirst(request: Request, cacheName: string): Promise<Response> {
  const cache = await caches.open(cacheName)
  
  try {
    const response = await fetch(request)
    if (response.ok) {
      cache.put(request, response.clone())
    }
    return response
  } catch (error) {
    console.log('🔄 Network failed, trying cache:', error)
    const cached = await cache.match(request)
    if (cached) {
      return cached
    }
    return new Response('Offline', { status: 503, statusText: 'Service Unavailable' })
  }
}

// Stale While Revalidate - Sirve cache, actualiza simultáneamente
async function staleWhileRevalidate(request: Request, cacheName: string): Promise<Response> {
  const cache = await caches.open(cacheName)
  const cached = await cache.match(request)
  
  const fetchPromise = fetch(request).then(response => {
    if (response.ok) {
      cache.put(request, response.clone())
    }
    return response
  }).catch(error => {
    console.error('❌ Fetch failed:', error)
    return cached || new Response('Offline', { status: 503, statusText: 'Service Unavailable' })
  })
  
  return cached || fetchPromise
}

// Cache First con Background Update
async function cacheFirstWithBackgroundUpdate(request: Request, cacheName: string): Promise<Response> {
  const cache = await caches.open(cacheName)
  const cached = await cache.match(request)
  
  if (cached) {
    // Actualizar en background sin bloquear
    updateCacheInBackground(request, cacheName)
    return cached
  }
  
  try {
    const response = await fetch(request)
    if (response.ok) {
      cache.put(request, response.clone())
    }
    return response
  } catch (error) {
    console.error('❌ Network request failed:', error)
    return new Response('Offline', { status: 503, statusText: 'Service Unavailable' })
  }
}

// Network Only - Solo red
async function networkOnly(request: Request): Promise<Response> {
  try {
    return await fetch(request)
  } catch (error) {
    console.error('❌ Network request failed:', error)
    return new Response('Offline', { status: 503, statusText: 'Service Unavailable' })
  }
}

// 🔥 ACTUALIZACIÓN EN BACKGROUND
async function updateCacheInBackground(request: Request, cacheName: string) {
  try {
    const cache = await caches.open(cacheName)
    const response = await fetch(request)
    if (response.ok) {
      cache.put(request, response)
    }
  } catch (error) {
    console.log('🔄 Background update failed:', error)
  }
}

// 🔥 BACKGROUND SYNC
self.addEventListener('sync', (event) => {
  console.log('🔄 Background sync triggered:', event.tag)
  
  if (event.tag === 'sync-messages') {
    event.waitUntil(syncMessages())
  } else if (event.tag === 'sync-posts') {
    event.waitUntil(syncPosts())
  }
})

// 🔥 PUSH NOTIFICATIONS
self.addEventListener('push', (event) => {
  console.log('📬 Push notification received:', event)
  
  const options = {
    body: event.data?.text() || 'Nueva notificación',
    icon: '/icons/icon-192x192.png',
    badge: '/icons/icon-72x72.png',
    tag: 'conectate-notification',
    renotify: true,
    requireInteraction: false,
    actions: [
      {
        action: 'view',
        title: 'Ver'
      },
      {
        action: 'dismiss',
        title: 'Descartar'
      }
    ]
  }
  
  event.waitUntil(
    self.registration.showNotification('Novvin', options)
  )
})

// 🔥 NOTIFICATION CLICK
self.addEventListener('notificationclick', (event) => {
  console.log('🔔 Notification clicked:', event)
  
  event.notification.close()
  
  if (event.action === 'view') {
    event.waitUntil(
      clients.openWindow('/')
    )
  } else if (event.action === 'dismiss') {
    // No hacer nada, solo cerrar
  } else {
    // Click en la notificación
    event.waitUntil(
      clients.openWindow('/')
    )
  }
})

// 🔥 MESSAGE HANDLING
self.addEventListener('message', (event) => {
  console.log('📨 Message received:', event.data)
  
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting()
  } else if (event.data && event.data.type === 'CACHE_UPDATE') {
    updateCache(event.data.url, event.data.cacheName)
  }
})

// 🔥 SYNC FUNCTIONS
async function syncMessages() {
  try {
    // Sincronizar mensajes pendientes
    console.log('🔄 Syncing messages...')
    // Implementar lógica de sincronización
  } catch (error) {
    console.error('❌ Error syncing messages:', error)
  }
}

async function syncPosts() {
  try {
    // Sincronizar posts pendientes
    console.log('🔄 Syncing posts...')
    // Implementar lógica de sincronización
  } catch (error) {
    console.error('❌ Error syncing posts:', error)
  }
}

// 🔥 CACHE MANAGEMENT
async function updateCache(url: string, cacheName: string) {
  try {
    const cache = await caches.open(cacheName)
    const response = await fetch(url)
    if (response.ok) {
      cache.put(url, response)
    }
  } catch (error) {
    console.error('❌ Error updating cache:', error)
  }
}

// 🔥 CLEANUP FUNCTIONS
async function cleanupOldCaches() {
  try {
    const cacheNames = await caches.keys()
    const oldCaches = cacheNames.filter(name => 
      name !== CACHE_NAME && 
      name !== STATIC_CACHE && 
      name !== DYNAMIC_CACHE && 
      name !== API_CACHE && 
      name !== MEDIA_CACHE
    )
    
    await Promise.all(oldCaches.map(name => caches.delete(name)))
    console.log('✅ Old caches cleaned')
  } catch (error) {
    console.error('❌ Error cleaning caches:', error)
  }
}

// 🔥 PERFORMANCE MONITORING
self.addEventListener('fetch', (event) => {
  const startTime = performance.now()
  
  event.waitUntil(
    (async () => {
      try {
        await fetch(event.request.clone())
      } catch (error) {
        // Error handling
      } finally {
        const endTime = performance.now()
        const duration = endTime - startTime
        
        if (duration > 5000) {
          console.warn('⚠️ Slow request detected:', event.request.url, duration + 'ms')
        }
      }
    })()
  )
})

// 🔥 ERROR HANDLING
self.addEventListener('error', (event) => {
  console.error('❌ Service Worker error:', event.error)
})

self.addEventListener('unhandledrejection', (event) => {
  console.error('❌ Unhandled promise rejection:', event.reason)
})

// 🔥 DEBUG INFO
console.log('🔥 Novvin Web Service Worker v2.0.0 loaded')
