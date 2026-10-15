import React from 'react';
import { NavLink } from 'react-router-dom';
import { 
  MessageCircle, 
  Users, 
  Video, 
  Mic, 
  Search, 
  Bell, 
  User, 
  Home,
  X
} from 'lucide-react';
import { cn } from '../../../core/utils/cn';

interface ModernNavigationProps {
  isOpen: boolean;
  onClose: () => void;
  currentView: 'mobile' | 'tablet' | 'desktop';
}

const navigationItems = [
  { icon: Home, label: 'Inicio', href: '/app/messages', id: 'home' },
  { icon: MessageCircle, label: 'Mensajes', href: '/app/messages', id: 'messages' },
  { icon: Users, label: 'Social', href: '/app/social', id: 'social' },
  { icon: Video, label: 'Videos', href: '/app/videos', id: 'videos' },
  { icon: Mic, label: 'Voz', href: '/app/voice', id: 'voice' },
  { icon: Search, label: 'Buscar', href: '/app/search', id: 'search' },
  { icon: Bell, label: 'Alertas', href: '/app/notifications', id: 'notifications' },
  { icon: User, label: 'Perfil', href: '/app/profile', id: 'profile' },
];

export function ModernNavigation({ isOpen, onClose, currentView }: ModernNavigationProps) {
  const location = window.location.pathname;

  const navigationClasses = cn(
    'fixed inset-x-0 bottom-0 z-50 bg-white dark:bg-slate-800 border-t border-slate-200 dark:border-slate-700',
    'transform transition-transform duration-300 ease-in-out',
    currentView === 'mobile' && (isOpen ? 'translate-y-0' : 'translate-y-full'),
    currentView === 'tablet' && (isOpen ? 'translate-y-0' : 'translate-y-full'),
    currentView === 'desktop' && 'hidden'
  );

  const overlayClasses = cn(
    'fixed inset-0 bg-black bg-opacity-50 z-40 transition-opacity duration-300',
    currentView === 'mobile' && (isOpen ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'),
    currentView === 'tablet' && (isOpen ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'),
    currentView === 'desktop' && 'hidden'
  );

  return (
    <>
      {/* Overlay */}
      <div className={overlayClasses} onClick={onClose} />

      {/* Bottom Navigation */}
      <div className={navigationClasses}>
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-slate-200 dark:border-slate-700">
          <div className="flex items-center space-x-3">
            <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-purple-600 rounded-lg flex items-center justify-center">
              <span className="text-white font-bold text-sm">C</span>
            </div>
            <h2 className="text-lg font-bold text-slate-900 dark:text-white">Menú</h2>
          </div>
          <button
            onClick={onClose}
            className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          >
            <X className="w-5 h-5 text-slate-600 dark:text-slate-400" />
          </button>
        </div>

        {/* Navigation Items */}
        <div className="p-4">
          <div className="grid grid-cols-4 gap-4">
            {navigationItems.map((item) => {
              const isActive = location === item.href || 
                              (item.id === 'social' && location.startsWith('/app/social')) ||
                              (item.id === 'messages' && location.startsWith('/app/messages'));
              
              return (
                <NavLink
                  key={item.id}
                  to={item.href}
                  onClick={onClose}
                  className={cn(
                    'flex flex-col items-center space-y-2 p-3 rounded-lg transition-all duration-200',
                    'text-xs font-medium',
                    isActive
                      ? 'bg-blue-50 text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                      : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-700 dark:hover:text-white'
                  )}
                >
                  <item.icon className="w-6 h-6" />
                  <span className="text-center">{item.label}</span>
                </NavLink>
              );
            })}
          </div>
        </div>
      </div>
    </>
  );
}
