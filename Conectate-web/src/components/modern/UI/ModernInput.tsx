import React from 'react';
import { cn } from '../../../core/utils/cn';

interface ModernInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  helperText?: string;
  icon?: React.ReactNode;
  iconPosition?: 'left' | 'right';
}

export function ModernInput({
  label,
  error,
  helperText,
  icon,
  iconPosition = 'left',
  className,
  ...props
}: ModernInputProps) {
  const inputId = React.useId();

  const inputClasses = cn(
    'w-full px-3 py-2 border rounded-lg transition-all duration-200',
    'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent',
    'placeholder-slate-400 dark:placeholder-slate-500',
    'bg-white dark:bg-slate-800 text-slate-900 dark:text-white',
    'border-slate-300 dark:border-slate-600',
    error && 'border-red-300 focus:ring-red-500 dark:border-red-600',
    icon && iconPosition === 'left' && 'pl-10',
    icon && iconPosition === 'right' && 'pr-10',
    className
  );

  return (
    <div className="space-y-2">
      {label && (
        <label
          htmlFor={inputId}
          className="block text-sm font-medium text-slate-700 dark:text-slate-300"
        >
          {label}
        </label>
      )}
      
      <div className="relative">
        {icon && iconPosition === 'left' && (
          <div className="absolute left-3 top-1/2 transform -translate-y-1/2 text-slate-400">
            {icon}
          </div>
        )}
        
        <input
          id={inputId}
          className={inputClasses}
          {...props}
        />
        
        {icon && iconPosition === 'right' && (
          <div className="absolute right-3 top-1/2 transform -translate-y-1/2 text-slate-400">
            {icon}
          </div>
        )}
      </div>
      
      {error && (
        <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
      )}
      
      {helperText && !error && (
        <p className="text-sm text-slate-500 dark:text-slate-400">{helperText}</p>
      )}
    </div>
  );
}

interface ModernTextareaProps extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string;
  error?: string;
  helperText?: string;
}

export function ModernTextarea({
  label,
  error,
  helperText,
  className,
  ...props
}: ModernTextareaProps) {
  const textareaId = React.useId();

  const textareaClasses = cn(
    'w-full px-3 py-2 border rounded-lg transition-all duration-200 resize-vertical',
    'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent',
    'placeholder-slate-400 dark:placeholder-slate-500',
    'bg-white dark:bg-slate-800 text-slate-900 dark:text-white',
    'border-slate-300 dark:border-slate-600',
    error && 'border-red-300 focus:ring-red-500 dark:border-red-600',
    className
  );

  return (
    <div className="space-y-2">
      {label && (
        <label
          htmlFor={textareaId}
          className="block text-sm font-medium text-slate-700 dark:text-slate-300"
        >
          {label}
        </label>
      )}
      
      <textarea
        id={textareaId}
        className={textareaClasses}
        {...props}
      />
      
      {error && (
        <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
      )}
      
      {helperText && !error && (
        <p className="text-sm text-slate-500 dark:text-slate-400">{helperText}</p>
      )}
    </div>
  );
}
