import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '@/utils/format';

const base =
  'inline-flex h-8 items-center gap-1.5 rounded-full bg-surface-2 px-3 text-xs font-semibold text-text transition-colors hover:bg-surface-3';

/** Capsule action used under posts and comments (comments, share, save…). */
export function ActionPill({
  children,
  to,
  onClick,
  active,
  pressed,
  label,
  className,
}: {
  children: ReactNode;
  to?: string;
  onClick?: () => void;
  active?: boolean;
  pressed?: boolean;
  label?: string;
  className?: string;
}) {
  const cls = cn(base, active && 'text-accent', className);
  if (to) {
    return (
      <Link to={to} className={cls} aria-label={label}>
        {children}
      </Link>
    );
  }
  return (
    <button type="button" onClick={onClick} className={cls} aria-pressed={pressed} aria-label={label}>
      {children}
    </button>
  );
}
