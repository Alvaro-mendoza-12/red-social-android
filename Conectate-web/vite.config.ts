import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 🔥 OPTIMIZED VITE CONFIG - BASADO EN BEST PRACTICES OFICIALES
// Implementación realista basada en documentación oficial de Vite
export default defineConfig({
  plugins: [react()],
  
  // 🔥 BEST PRACTICE: Optimización de resolución de módulos
  resolve: {
    // 🔥 BEST PRACTICE: Extensiones explícitas para evitar filesystem checks
    extensions: ['.ts', '.tsx', '.js', '.jsx', '.json'],
    
    // 🔥 BEST PRACTICE: Alias para imports más rápidos
    alias: {
      '@': '/src',
      '@components': '/src/components',
      '@pages': '/src/pages',
      '@hooks': '/src/hooks',
      '@utils': '/src/utils',
      '@styles': '/src/styles',
      '@assets': '/src/assets'
    }
  },
  
  // 🔥 BEST PRACTICE: Optimización de build
  build: {
    // 🔥 BEST PRACTICE: Target moderno para mejor optimización
    target: 'esnext',
    
    // 🔥 BEST PRACTICE: Minificación agresiva
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: true, // Eliminar console.log en producción
        drop_debugger: true, // Eliminar debugger
        pure_funcs: ['console.log', 'console.info', 'console.debug']
      },
      mangle: {
        safari10: true
      }
    },
    
    // 🔥 BEST PRACTICE: Code splitting inteligente
    rollupOptions: {
      output: {
        // 🔥 BEST PRACTICE: Separar vendor code
        manualChunks: {
          vendor: ['react', 'react-dom'],
          router: ['react-router-dom'],
          ui: ['framer-motion', 'lucide-react'],
          api: ['@tanstack/react-query', '@supabase/supabase-js'],
          realtime: ['socket.io-client']
        },
        
        // 🔥 BEST PRACTICE: Nombres de chunks optimizados
        chunkFileNames: (chunkInfo) => {
          const facadeModuleId = chunkInfo.facadeModuleId
            ? chunkInfo.facadeModuleId.split('/').pop()?.replace(/\.[^.]*$/, '')
            : 'chunk'
          return `js/${facadeModuleId}-[hash].js`
        },
        
        entryFileNames: 'js/[name]-[hash].js',
        assetFileNames: (assetInfo) => {
          const info = assetInfo.name?.split('.') || []
          const extType = info[info.length - 1]
          return `${extType}/[name]-[hash].[ext]`
        }
      }
    },
    
    // 🔥 BEST PRACTICE: Chunk size warning limit
    chunkSizeWarningLimit: 1000,
    
    // 🔥 BEST PRACTICE: Source maps optimizadas
    sourcemap: true
  },
  
  // 🔥 BEST PRACTICE: Optimización de servidor de desarrollo
  server: {
    // 🔥 BEST PRACTICE: Host específico para desarrollo
    host: true,
    
    // 🔥 BEST PRACTICE: Puerto configurable
    port: 5173,
    
    // 🔥 BEST PRACTICE: Hot module replacement optimizado
    hmr: {
      overlay: true,
      port: 5174
    },
    
    // 🔥 BEST PRACTICE: Headers para caching
    headers: {
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*'
    }
  },
  
  // 🔥 BEST PRACTICE: Optimización de preview
  preview: {
    port: 4173,
    host: true
  },
  
  // 🔥 BEST PRACTICE: Optimización de dependencias
  optimizeDeps: {
    // 🔥 BEST PRACTICE: Pre-bundle de dependencias comunes
    include: [
      'react',
      'react-dom',
      'react-router-dom',
      'framer-motion',
      'lucide-react',
      'socket.io-client',
      '@tanstack/react-query',
      '@supabase/supabase-js'
    ],
    
    // 🔥 BEST PRACTICE: Excluir dependencias problemáticas
    exclude: [
      'some-problematic-package'
    ]
  },
  
  // 🔥 BEST PRACTICE: Configuración de CSS
  css: {
    // 🔥 BEST PRACTICE: Modules CSS con optimización
    modules: {
      localsConvention: 'camelCase'
    },
    
        
    // 🔥 BEST PRACTICE: Dev sourcemaps para CSS
    devSourcemap: true
  },
  
  // 🔥 BEST PRACTICE: Configuración de assets
  assetsInclude: ['**/*.png', '**/*.jpg', '**/*.jpeg', '**/*.gif', '**/*.svg', '**/*.webp'],
  
  // 🔥 BEST PRACTICE: Configuración de worker
  worker: {
    format: 'es',
    rollupOptions: {
      output: {
        entryFileNames: 'js/[name]-[hash].js',
        chunkFileNames: 'js/[name]-[hash].js'
      }
    }
  },
  
  // 🔥 BEST PRACTICE: Configuración experimental
  experimental: {
    // 🔥 BEST PRACTICE: Build optimizado
    renderBuiltUrl: (filename, { hostType }) => {
      if (hostType === 'js') {
        return { js: `/${filename}` }
      } else {
        return { relative: true }
      }
    }
  },
  
  // 🔥 BEST PRACTICE: Configuración de entorno
  define: {
    // 🔥 BEST PRACTICE: Variables de entorno
    __APP_VERSION__: JSON.stringify(process.env.npm_package_version),
    __BUILD_TIME__: JSON.stringify(new Date().toISOString())
  }
})
