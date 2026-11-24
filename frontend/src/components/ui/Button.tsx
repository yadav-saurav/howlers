import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { cn } from '@/utils/format';
import { Spinner } from './Spinner';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'outline';
export type ButtonSize = 'sm' | 'md';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
}

const variants: Record<ButtonVariant, string> = {
  primary: 'bg-brand text-brand-fg hover:bg-brand/90 font-semibold',
  secondary: 'bg-surface-2 text-text hover:bg-surface-3 font-semibold',
  outline: 'border border-text/40 text-text hover:border-text font-semibold',
  ghost: 'text-muted hover:text-text hover:bg-surface-2',
  danger: 'bg-danger text-danger-fg hover:bg-danger/90 font-semibold',
};
const sizes: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-sm gap-1.5',
  md: 'h-10 px-4 text-[14px] gap-2',
};

export function buttonClasses(variant: ButtonVariant = 'primary', size: ButtonSize = 'md', extra?: string) {
  return cn(
    'inline-flex items-center justify-center rounded-full font-medium transition-colors select-none whitespace-nowrap',
    'disabled:opacity-50 disabled:pointer-events-none',
    variants[variant],
    sizes[size],
    extra,
  );
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', loading, className, children, disabled, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={buttonClasses(variant, size, className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading && <Spinner size={14} />}
      {children}
    </button>
  );
});

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  label: string;
  size?: 'sm' | 'md';
  active?: boolean;
}

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { label, size = 'md', active, className, children, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      title={label}
      className={cn(
        'inline-flex items-center justify-center rounded-full transition-colors text-muted hover:text-text hover:bg-surface-2',
        'disabled:opacity-50 disabled:pointer-events-none',
        size === 'sm' ? 'h-8 w-8' : 'h-10 w-10',
        active && 'text-text bg-surface-2',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
});
