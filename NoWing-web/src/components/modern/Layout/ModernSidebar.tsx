import React from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { 
  MessageCircle, 
  Users, 
  Video, 
  Mic, 
  Search, 
  Bell, 
  User, 
  Settings,
  Home,
  Hash,
  Trophy,
  Shield,
  LogOut
} from 'lucide-react';
import { cn } from '../../../core/utils/cn';

interface ModernSidebarProps {
  isOpen: boolean;
  onClose: () => void;
  currentView: 'mobile' | 'tablet' | 'desktop';
}

const navigationItems = [
  { icon: Home, label: 'Inicio', href: '/app/messages', id: 'home' },
  { icon: MessageCircle, label: 'Mensajes', href: '/app/messages', id: 'messages' },
  { icon: Users, label: 'Social', href: '/app/social', id: 'social' },
  { icon: Video, label: 'Videos', href: '/app/videos', id: 'videos' },
  { icon: Mic, label: 'Salas de Voz', href: '/app/voice', id: 'voice' },
  { icon: Search, label: 'Buscar', href: '/app/search', id: 'search' },
  { icon: Bell, label: 'Notificaciones', href: '/app/notifications', id: 'notifications' },
  { icon: User, label: 'Perfil', href: '/app/profile', id: 'profile' },
  { icon: Hash, label: 'Retos', href: '/app/challenges', id: 'challenges' },
  { icon: Trophy, label: 'Ranking', href: '/app/challenges/ranking', id: 'ranking' },
];

const secondaryItems = [
  { icon: Settings, label: 'Configuración', href: '/app/settings', id: 'settings' },
  { icon: Shield, label: 'Admin', href: '/app/admin', id: 'admin' },
];

export function ModernSidebar({ isOpen, onClose, currentView }: ModernSidebarProps) {
  const location = useLocation();

  const sidebarClasses = cn(
    'fixed inset-y-0 left-0 z-50 w-64 bg-white dark:bg-slate-800 shadow-xl',
    'transform transition-transform duration-300 ease-in-out',
    'border-r border-slate-200 dark:border-slate-700',
    currentView === 'mobile' && (isOpen ? 'translate-x-0' : '-translate-x-full'),
    currentView === 'tablet' && (isOpen ? 'translate-x-0' : '-translate-x-full'),
    currentView === 'desktop' && 'translate-x-0'
  );

  const overlayClasses = cn(
    'fixed inset-0 bg-black bg-opacity-50 z-40 transition-opacity duration-300',
    currentView === 'mobile' && (isOpen ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'),
    currentView === 'tablet' && (isOpen ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'),
    currentView === 'desktop' && 'hidden'
  );

  const isDesktop = currentView === 'desktop';

  return (
    <>
      {/* Overlay for mobile/tablet */}
      <div className={overlayClasses} onClick={onClose} />

      {/* Sidebar */}
      <div className={sidebarClasses}>
        <div className="flex flex-col h-full">
          {/* Header */}
          <div className="flex items-center justify-between p-4 border-b border-slate-200 dark:border-slate-700">
            <div className="flex items-center space-x-3">
              <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-purple-600 rounded-lg flex items-center justify-center">
                <span className="text-white font-bold text-sm">C</span>
              </div>
              <h1 className="text-lg font-bold text-slate-900 dark:text-white">NOVVIN</h1>
            </div>
            {!isDesktop && (
              <button
                onClick={onClose}
                className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            )}
          </div>

          {/* Navigation */}
          <nav className="flex-1 overflow-y-auto p-4">
            <div className="space-y-2">
              {navigationItems.map((item) => {
                const isActive = location.pathname === item.href || 
                                (item.id === 'social' && location.pathname.startsWith('/app/social')) ||
                                (item.id === 'messages' && location.pathname.startsWith('/app/messages'));
                
                return (
                  <NavLink
                    key={item.id}
                    to={item.href}
                    onClick={() => !isDesktop && onClose()}
                    className={cn(
                      'flex items-center space-x-3 px-3 py-2 rounded-lg transition-all duration-200',
                      'text-sm font-medium',
                      isActive
                        ? 'bg-blue-50 text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                        : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-700 dark:hover:text-white'
                    )}
                  >
                    <item.icon className="w-5 h-5" />
                    <span>{item.label}</span>
                  </NavLink>
                );
              })}
            </div>

            <div className="mt-8 space-y-2">
              {secondaryItems.map((item) => {
                const isActive = location.pathname === item.href;
                
                return (
                  <NavLink
                    key={item.id}
                    to={item.href}
                    onClick={() => !isDesktop && onClose()}
                    className={cn(
                      'flex items-center space-x-3 px-3 py-2 rounded-lg transition-all duration-200',
                      'text-sm font-medium',
                      isActive
                        ? 'bg-slate-100 text-slate-900 dark:bg-slate-700 dark:text-white'
                        : 'text-slate-500 hover:bg-slate-50 hover:text-slate-700 dark:text-slate-500 dark:hover:bg-slate-700 dark:hover:text-slate-300'
                    )}
                  >
                    <item.icon className="w-5 h-5" />
                    <span>{item.label}</span>
                  </NavLink>
                );
              })}
            </div>
          </nav>

          {/* Footer */}
          <div className="p-4 border-t border-slate-200 dark:border-slate-700">
            <button className="flex items-center space-x-3 w-full px-3 py-2 rounded-lg text-sm font-medium text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-900/20 transition-all duration-200">
              <LogOut className="w-5 h-5" />
              <span>Cerrar Sesión</span>
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
