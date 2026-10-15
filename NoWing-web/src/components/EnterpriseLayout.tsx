import React, { useState, useEffect, useCallback, useMemo } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { 
  Bell, 
  Search, 
  Settings, 
  Users, 
  Video, 
  MessageSquare, 
  User, 
  Mic, 
  Music, 
  Home,
  TrendingUp,
  Heart,
  Share2,
  Bookmark,
  MoreHorizontal,
  LogOut,
  Moon,
  Sun,
  Wifi,
  WifiOff,
  ChevronLeft,
  ChevronRight
} from 'lucide-react'

/**
 * 🔥 ENTERPRISE LAYOUT - NIVEL INSTAGRAM/FACEBOOK/TIKTOK
 * Layout ultra-moderno con responsive design y performance optimizada
 */
interface EnterpriseLayoutProps {
  children: React.ReactNode
}

export const EnterpriseLayout: React.FC<EnterpriseLayoutProps> = ({ children }) => {
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false)
  const [isDarkMode, setIsDarkMode] = useState(false)
  const [isOnline, setIsOnline] = useState(navigator.onLine)
  const [notifications, setNotifications] = useState(3)
  const [activeSection, setActiveSection] = useState('feed')
  const location = useLocation()

  // 🔥 DETECCIÓN AUTOMÁTICA DE DISPOSITIVO
  const [isMobile, setIsMobile] = useState(false)
  const [isTablet, setIsTablet] = useState(false)
  const [isDesktop, setIsDesktop] = useState(false)

  useEffect(() => {
    const handleResize = () => {
      const width = window.innerWidth
      setIsMobile(width < 768)
      setIsTablet(width >= 768 && width < 1024)
      setIsDesktop(width >= 1024)
      
      // Auto-collapse sidebar en mobile
      if (width < 768) {
        setIsSidebarCollapsed(true)
      }
    }

    handleResize()
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [])

  // 🔥 MONITOREO DE CONEXIÓN
  useEffect(() => {
    const handleOnline = () => setIsOnline(true)
    const handleOffline = () => setIsOnline(false)

    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)

    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }
  }, [])

  // 🔥 NAVEGACIÓN PRINCIPAL - ESTILO INSTAGRAM
  const primaryNavItems = useMemo(() => [
    { 
      id: 'home', 
      label: 'Inicio', 
      icon: Home, 
      path: '/app',
      active: location.pathname === '/app' || location.pathname.startsWith('/app/feed')
    },
    { 
      id: 'search', 
      label: 'Buscar', 
      icon: Search, 
      path: '/app/search',
      active: location.pathname.startsWith('/app/search')
    },
    { 
      id: 'reels', 
      label: 'Reels', 
      icon: Video, 
      path: '/app/videos',
      active: location.pathname.startsWith('/app/videos')
    },
    { 
      id: 'messages', 
      label: 'Mensajes', 
      icon: MessageSquare, 
      path: '/app/messages',
      active: location.pathname.startsWith('/app/messages'),
      badge: notifications
    },
    { 
      id: 'notifications', 
      label: 'Notificaciones', 
      icon: Bell, 
      path: '/app/notifications',
      active: location.pathname.startsWith('/app/notifications'),
      badge: 5
    },
    { 
      id: 'profile', 
      label: 'Perfil', 
      icon: User, 
      path: '/app/profile',
      active: location.pathname.startsWith('/app/profile')
    }
  ], [location.pathname, notifications])

  // 🔥 NAVEGACIÓN SECUNDARIA - MODO VOZ Y DISCO
  const secondaryNavItems = useMemo(() => [
    { 
      id: 'voice-rooms', 
      label: 'Salas de Voz', 
      icon: Mic, 
      path: '/app/voice',
      active: location.pathname.startsWith('/app/voice'),
      color: 'text-purple-500'
    },
    { 
      id: 'disco', 
      label: 'Disco', 
      icon: Music, 
      path: '/app/disco',
      active: location.pathname.startsWith('/app/disco'),
      color: 'text-pink-500'
    },
    { 
      id: 'social', 
      label: 'Social', 
      icon: Users, 
      path: '/app/social',
      active: location.pathname.startsWith('/app/social')
    },
    { 
      id: 'trending', 
      label: 'Trending', 
      icon: TrendingUp, 
      path: '/app/trending',
      active: location.pathname.startsWith('/app/trending')
    }
  ], [location.pathname])

  // 🔥 TOGGLE SIDEBAR
  const toggleSidebar = useCallback(() => {
    setIsSidebarCollapsed(prev => !prev)
  }, [])

  // 🔥 TOGGLE DARK MODE
  const toggleDarkMode = useCallback(() => {
    setIsDarkMode(prev => !prev)
    document.documentElement.classList.toggle('dark')
  }, [])

  // 🔥 ANIMATIONS
  const sidebarVariants = {
    expanded: { width: 280, x: 0 },
    collapsed: { width: 80, x: 0 },
    mobile: { width: '100%', x: isSidebarCollapsed ? -300 : 0 }
  }

  const navItemVariants = {
    hidden: { opacity: 0, x: -20 },
    visible: { opacity: 1, x: 0 }
  }

  return (
    <div className={`min-h-screen bg-gradient-to-br from-pink-50 via-white to-purple-50 dark:from-gray-900 dark:via-gray-800 dark:to-gray-900 transition-colors duration-300`}>
      
      {/* 🔥 SIDEBAR - ESTILO MODERNO */}
      <AnimatePresence>
        {(!isMobile || !isSidebarCollapsed) && (
          <motion.aside
            variants={sidebarVariants}
            initial={isMobile ? 'mobile' : (isSidebarCollapsed ? 'collapsed' : 'expanded')}
            animate={isMobile ? 'mobile' : (isSidebarCollapsed ? 'collapsed' : 'expanded')}
            exit={isMobile ? 'mobile' : 'collapsed'}
            transition={{ duration: 0.3, ease: 'easeInOut' }}
            className={`
              fixed left-0 top-0 h-full bg-white/90 dark:bg-gray-900/90 backdrop-blur-xl border-r border-gray-200/50 dark:border-gray-700/50
              z-50 shadow-2xl
              ${isMobile ? 'w-full' : ''}
            `}
          >
            <div className="flex flex-col h-full">
              
              {/* 🔥 HEADER */}
              <div className="flex items-center justify-between p-4 border-b border-gray-200/50 dark:border-gray-700/50">
                <AnimatePresence mode="wait">
                  {!isSidebarCollapsed || isMobile ? (
                    <motion.div
                      key="expanded"
                      initial={{ opacity: 0, scale: 0.8 }}
                      animate={{ opacity: 1, scale: 1 }}
                      exit={{ opacity: 0, scale: 0.8 }}
                      className="flex items-center space-x-3"
                    >
                      <div className="w-10 h-10 bg-gradient-to-br from-pink-500 to-purple-600 rounded-xl flex items-center justify-center">
                        <span className="text-white font-bold text-lg">C</span>
                      </div>
                      <div>
                        <h1 className="font-bold text-gray-900 dark:text-white">Novvin</h1>
                        <p className="text-xs text-gray-500 dark:text-gray-400">Social Platform</p>
                      </div>
                    </motion.div>
                  ) : (
                    <motion.div
                      key="collapsed"
                      initial={{ opacity: 0, scale: 0.8 }}
                      animate={{ opacity: 1, scale: 1 }}
                      exit={{ opacity: 0, scale: 0.8 }}
                      className="w-10 h-10 bg-gradient-to-br from-pink-500 to-purple-600 rounded-xl flex items-center justify-center mx-auto"
                    >
                      <span className="text-white font-bold text-lg">C</span>
                    </motion.div>
                  )}
                </AnimatePresence>
                
                {!isMobile && (
                  <button
                    onClick={toggleSidebar}
                    aria-label={isSidebarCollapsed ? 'Expandir menú' : 'Contraer menú'}
                    className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
                  >
                    {isSidebarCollapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronLeft className="w-4 h-4" />}
                  </button>
                )}
              </div>

              {/* 🔥 NAVEGACIÓN PRINCIPAL */}
              <nav className="flex-1 p-4 space-y-2 overflow-y-auto">
                {primaryNavItems.map((item, index) => (
                  <motion.div
                    key={item.id}
                    variants={navItemVariants}
                    initial="hidden"
                    animate="visible"
                    transition={{ delay: index * 0.05 }}
                  >
                    <NavLink
                      to={item.path}
                      className={({ isActive }) => `
                        group relative flex items-center ${isSidebarCollapsed && !isMobile ? 'justify-center' : 'justify-start'} 
                        px-3 py-3 rounded-xl transition-all duration-200
                        ${isActive 
                          ? 'bg-gradient-to-r from-pink-500 to-purple-600 text-white shadow-lg' 
                          : 'hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300'
                        }
                      `}
                    >
                      <item.icon className={`w-5 h-5 ${!item.active && 'group-hover:scale-110 transition-transform'}`} />
                      
                      {(!isSidebarCollapsed || isMobile) && (
                        <span className="ml-3 font-medium truncate">{item.label}</span>
                      )}
                      
                      {item.badge && item.badge > 0 && (
                        <span className={`
                          absolute -top-1 -right-1 w-5 h-5 bg-red-500 text-white text-xs rounded-full flex items-center justify-center
                          ${isSidebarCollapsed && !isMobile ? 'scale-90' : ''}
                        `}>
                          {item.badge > 9 ? '9+' : item.badge}
                        </span>
                      )}
                    </NavLink>
                  </motion.div>
                ))}

                {/* 🔥 SEPARADOR */}
                <div className="my-4 border-t border-gray-200/50 dark:border-gray-700/50" />

                {/* 🔥 NAVEGACIÓN SECUNDARIA */}
                {secondaryNavItems.map((item, index) => (
                  <motion.div
                    key={item.id}
                    variants={navItemVariants}
                    initial="hidden"
                    animate="visible"
                    transition={{ delay: (primaryNavItems.length + index) * 0.05 }}
                  >
                    <NavLink
                      to={item.path}
                      className={({ isActive }) => `
                        group relative flex items-center ${isSidebarCollapsed && !isMobile ? 'justify-center' : 'justify-start'} 
                        px-3 py-3 rounded-xl transition-all duration-200
                        ${isActive 
                          ? `${item.color} bg-gradient-to-r from-purple-500/20 to-pink-500/20 shadow-lg` 
                          : 'hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300'
                        }
                      `}
                    >
                      <item.icon className={`w-5 h-5 ${item.color} ${!item.active && 'group-hover:scale-110 transition-transform'}`} />
                      
                      {(!isSidebarCollapsed || isMobile) && (
                        <span className={`ml-3 font-medium truncate ${item.color}`}>{item.label}</span>
                      )}
                    </NavLink>
                  </motion.div>
                ))}
              </nav>

              {/* 🔥 FOOTER */}
              <div className="p-4 border-t border-gray-200/50 dark:border-gray-700/50 space-y-2">
                <button
                  onClick={toggleDarkMode}
                  className="w-full flex items-center justify-center px-3 py-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
                >
                  {isDarkMode ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
                  {(!isSidebarCollapsed || isMobile) && <span className="ml-3 text-sm">Modo {isDarkMode ? 'Claro' : 'Oscuro'}</span>}
                </button>

                <div className="flex items-center justify-center px-3 py-2">
                  {isOnline ? (
                    <Wifi className="w-4 h-4 text-green-500" />
                  ) : (
                    <WifiOff className="w-4 h-4 text-red-500" />
                  )}
                  {(!isSidebarCollapsed || isMobile) && (
                    <span className={`ml-3 text-sm ${isOnline ? 'text-green-500' : 'text-red-500'}`}>
                      {isOnline ? 'Conectado' : 'Desconectado'}
                    </span>
                  )}
                </div>
              </div>
            </div>
          </motion.aside>
        )}
      </AnimatePresence>

      {/* 🔥 MOBILE OVERLAY */}
      {isMobile && !isSidebarCollapsed && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 bg-black/50 z-40"
          onClick={toggleSidebar}
        />
      )}

      {/* 🔥 MAIN CONTENT */}
      <div className={`
        flex-1 transition-all duration-300
        ${isMobile ? 'ml-0' : (isSidebarCollapsed ? 'ml-20' : 'ml-72')}
      `}>
        
        {/* 🔥 TOP BAR - ESTILO MODERNO */}
        <header className="sticky top-0 z-30 bg-white/90 dark:bg-gray-900/90 backdrop-blur-xl border-b border-gray-200/50 dark:border-gray-700/50">
          <div className="flex items-center justify-between px-4 py-3">
            
            {/* 🔥 MOBILE MENU BUTTON */}
            {isMobile && (
              <button
                onClick={toggleSidebar}
                className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
              >
                <div className="w-6 h-0.5 bg-gray-600 dark:bg-gray-300 relative">
                  <div className="absolute -top-2 w-6 h-0.5 bg-gray-600 dark:bg-gray-300"></div>
                  <div className="absolute top-2 w-6 h-0.5 bg-gray-600 dark:bg-gray-300"></div>
                </div>
              </button>
            )}

            {/* 🔥 SEARCH BAR */}
            <div className="flex-1 max-w-md mx-4">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
                <input
                  type="text"
                  placeholder="Buscar en Novvin..."
                  className="w-full pl-10 pr-4 py-2 bg-gray-100 dark:bg-gray-800 rounded-full focus:outline-none focus:ring-2 focus:ring-pink-500 focus:bg-white dark:focus:bg-gray-700 transition-all"
                />
              </div>
            </div>

            {/* 🔥 USER ACTIONS */}
            <div className="flex items-center space-x-2">
              <button className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors relative">
                <Bell className="w-5 h-5" />
                {notifications > 0 && (
                  <span className="absolute -top-1 -right-1 w-5 h-5 bg-red-500 text-white text-xs rounded-full flex items-center justify-center">
                    {notifications}
                  </span>
                )}
              </button>
              
              <button className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors">
                <Settings className="w-5 h-5" />
              </button>

              {/* 🔥 USER AVATAR */}
              <div className="w-8 h-8 bg-gradient-to-br from-pink-500 to-purple-600 rounded-full flex items-center justify-center">
                <span className="text-white font-bold text-sm">U</span>
              </div>
            </div>
          </div>
        </header>

        {/* 🔥 PAGE CONTENT */}
        <main className="flex-1 overflow-hidden">
          <div className="h-full overflow-y-auto">
            <AnimatePresence mode="wait">
              <motion.div
                key={location.pathname}
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -20 }}
                transition={{ duration: 0.3 }}
                className="p-4"
              >
                {children}
              </motion.div>
            </AnimatePresence>
          </div>
        </main>
      </div>
    </div>
  )
}
