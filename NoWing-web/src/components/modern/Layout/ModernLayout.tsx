import React from 'react';
import { Outlet } from 'react-router-dom';
import { cn } from '../../../core/utils/cn';
import { ModernSidebar } from './ModernSidebar';
import { ModernHeader } from './ModernHeader';
import { ModernNavigation } from './ModernNavigation';

interface ModernLayoutProps {
  children?: React.ReactNode;
  className?: string;
}

export function ModernLayout({ children, className }: ModernLayoutProps) {
  const [sidebarOpen, setSidebarOpen] = React.useState(false);
  const [currentView, setCurrentView] = React.useState<'mobile' | 'tablet' | 'desktop'>('desktop');

  React.useEffect(() => {
    const handleResize = () => {
      if (window.innerWidth < 768) {
        setCurrentView('mobile');
      } else if (window.innerWidth < 1024) {
        setCurrentView('tablet');
      } else {
        setCurrentView('desktop');
      }
    };

    handleResize();
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const layoutClasses = cn(
    'min-h-screen bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-900 dark:to-slate-800',
    'transition-all duration-300 ease-in-out',
    className
  );

  const contentClasses = cn(
    'flex-1 overflow-hidden',
    'transition-all duration-300 ease-in-out'
  );

  if (currentView === 'mobile') {
    return (
      <div className={layoutClasses}>
        <ModernHeader
          onMenuClick={() => setSidebarOpen(!sidebarOpen)}
          currentView={currentView}
        />
        
        <main className={contentClasses}>
          <div className="h-full overflow-y-auto">
            {children || <Outlet />}
          </div>
        </main>

        <ModernNavigation
          isOpen={sidebarOpen}
          onClose={() => setSidebarOpen(false)}
          currentView={currentView}
        />
      </div>
    );
  }

  return (
    <div className={layoutClasses}>
      <ModernSidebar
        isOpen={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        currentView={currentView}
      />
      
      <div className="flex flex-col flex-1 overflow-hidden">
        <ModernHeader
          onMenuClick={() => setSidebarOpen(!sidebarOpen)}
          currentView={currentView}
        />
        
        <main className={contentClasses}>
          <div className="h-full overflow-y-auto p-4 lg:p-6">
            {children || <Outlet />}
          </div>
        </main>
      </div>
    </div>
  );
}
