import React from 'react';
import { cn } from '../../core/utils/cn';

interface ModernCardProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'default' | 'outlined' | 'elevated';
  padding?: 'none' | 'sm' | 'md' | 'lg';
  hover?: boolean;
}

export function ModernCard({
  children,
  className,
  variant = 'default',
  padding = 'md',
  hover = false,
  ...props
}: ModernCardProps) {
  const baseClasses = 'rounded-lg transition-all duration-200';

  const variantClasses = {
    default: 'bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700',
    outlined: 'bg-white dark:bg-slate-800 border-2 border-slate-300 dark:border-slate-600',
    elevated: 'bg-white dark:bg-slate-800 shadow-lg border border-slate-200 dark:border-slate-700'
  };

  const paddingClasses = {
    none: '',
    sm: 'p-3',
    md: 'p-4',
    lg: 'p-6'
  };

  const hoverClasses = hover ? 'hover:shadow-md hover:scale-[1.02] dark:hover:shadow-slate-800' : '';

  const classes = cn(
    baseClasses,
    variantClasses[variant],
    paddingClasses[padding],
    hoverClasses,
    className
  );

  return (
    <div className={classes} {...props}>
      {children}
    </div>
  );
}

interface ModernCardHeaderProps extends React.HTMLAttributes<HTMLDivElement> {
  title?: string;
  description?: string;
  action?: React.ReactNode;
}

export function ModernCardHeader({
  title,
  description,
  action,
  className,
  children,
  ...props
}: ModernCardHeaderProps) {
  return (
    <div className={cn('flex items-center justify-between pb-4 border-b border-slate-200 dark:border-slate-700', className)} {...props}>
      <div>
        {title && <h3 className="text-lg font-semibold text-slate-900 dark:text-white">{title}</h3>}
        {description && <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">{description}</p>}
        {children}
      </div>
      {action && <div>{action}</div>}
    </div>
  );
}

interface ModernCardContentProps extends React.HTMLAttributes<HTMLDivElement> {}

export function ModernCardContent({
  className,
  children,
  ...props
}: ModernCardContentProps) {
  return (
    <div className={cn('py-4', className)} {...props}>
      {children}
    </div>
  );
}

interface ModernCardFooterProps extends React.HTMLAttributes<HTMLDivElement> {}

export function ModernCardFooter({
  className,
  children,
  ...props
}: ModernCardFooterProps) {
  return (
    <div className={cn('flex items-center justify-between pt-4 border-t border-slate-200 dark:border-slate-700', className)} {...props}>
      {children}
    </div>
  );
}
