import React from 'react';
import { cn } from '../../core/utils/cn';
import { Loader2 } from 'lucide-react';

interface ModernButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'destructive';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
  icon?: React.ReactNode;
  iconPosition?: 'left' | 'right';
}

export function ModernButton({
  children,
  className,
  variant = 'primary',
  size = 'md',
  loading = false,
  icon,
  iconPosition = 'left',
  disabled,
  ...props
}: ModernButtonProps) {
  const baseClasses = 'inline-flex items-center justify-center font-medium transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed';

  const variantClasses = {
    primary: 'bg-blue-600 text-white hover:bg-blue-700 focus:ring-blue-500 dark:bg-blue-500 dark:hover:bg-blue-600',
    secondary: 'bg-slate-100 text-slate-900 hover:bg-slate-200 focus:ring-slate-500 dark:bg-slate-700 dark:text-white dark:hover:bg-slate-600',
    outline: 'border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 focus:ring-blue-500 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700',
    ghost: 'text-slate-700 hover:bg-slate-100 focus:ring-slate-500 dark:text-slate-300 dark:hover:bg-slate-700',
    destructive: 'bg-red-600 text-white hover:bg-red-700 focus:ring-red-500 dark:bg-red-500 dark:hover:bg-red-600'
  };

  const sizeClasses = {
    sm: 'px-3 py-1.5 text-sm rounded-md',
    md: 'px-4 py-2 text-sm rounded-lg',
    lg: 'px-6 py-3 text-base rounded-lg'
  };

  const classes = cn(
    baseClasses,
    variantClasses[variant],
    sizeClasses[size],
    className
  );

  const renderIcon = () => {
    if (loading) {
      return <Loader2 className="w-4 h-4 animate-spin" />;
    }
    return icon;
  };

  return (
    <button
      className={classes}
      disabled={disabled || loading}
      {...props}
    >
      {iconPosition === 'left' && renderIcon() && (
        <span className={children ? 'mr-2' : ''}>{renderIcon()}</span>
      )}
      {children}
      {iconPosition === 'right' && renderIcon() && (
        <span className={children ? 'ml-2' : ''}>{renderIcon()}</span>
      )}
    </button>
  );
}
