import type { ReactNode } from 'react';
import { sizedUrl } from '@/utils/images';
import { cn } from '@/utils/format';

export function Badge({
  children,
  tone = 'neutral',
  color,
  className,
}: {
  children: ReactNode;
  tone?: 'neutral' | 'accent' | 'danger' | 'warning' | 'info';
  color?: string;
  className?: string;
}) {
  const tones = {
    neutral: 'bg-surface-2 text-muted border-border',
    accent: 'bg-accent/10 text-accent border-accent/30',
    danger: 'bg-danger/10 text-danger border-danger/30',
    warning: 'bg-warning/10 text-warning border-warning/30',
    info: 'bg-info/10 text-info border-info/30',
  };
  return (
    <span
      className={cn(
        'inline-flex h-5 items-center gap-1 rounded-sm border px-1.5 text-[11px] font-medium leading-none',
        !color && tones[tone],
        color && 'text-text',
        className,
      )}
      // A custom colour (e.g. a community's flair) only tints the chip and its dot. The label stays in the text colour,
      // because an arbitrary hex can't be trusted to reach 4.5:1 against both themes.
      style={color ? { borderColor: `${color}66`, backgroundColor: `${color}1f` } : undefined}
    >
      {color && <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: color }} />}
      {children}
    </span>
  );
}

const avatarSizes = { xs: 'h-5 w-5 text-[9px]', sm: 'h-6 w-6 text-[10px]', md: 'h-9 w-9 text-xs', lg: 'h-16 w-16 text-lg', xl: 'h-24 w-24 text-2xl' };

/** Deterministic hue from a string so initials avatars are stable but varied. */
function hueOf(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
  return h;
}

export function Avatar({
  src,
  name,
  size = 'md',
  square,
  className,
}: {
  src?: string | null;
  name: string;
  size?: keyof typeof avatarSizes;
  square?: boolean;
  className?: string;
}) {
  const shape = square ? 'rounded-md' : 'rounded-full';
  if (src) {
    return (
      <img
        // Avatars and icons are at most 96 px: the 320 px variant is plenty (the API falls back to the original).
        src={sizedUrl(src, 320)}
        alt=""
        loading="lazy"
        decoding="async"
        className={cn('shrink-0 object-cover bg-surface-2', shape, avatarSizes[size], className)}
      />
    );
  }
  const initials = name.replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() || '?';
  // The initials are decorative (the name is always next to the avatar or in its control's label). They're drawn as
  // generated content so they never become part of a button's visible-text label (WCAG 2.5.3 label in name).
  return (
    <span
      aria-hidden="true"
      data-initials={initials}
      className={cn(
        'inline-flex shrink-0 items-center justify-center font-semibold before:content-[attr(data-initials)]',
        shape,
        avatarSizes[size],
        className,
      )}
      style={{ backgroundColor: `hsl(${hueOf(name)} 28% 30%)`, color: `hsl(${hueOf(name)} 40% 88%)` }}
    />
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cn('animate-shimmer rounded-md bg-surface-2', className)} />;
}

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return <section className={cn('rounded-lg border border-border bg-surface', className)}>{children}</section>;
}

export function CardHeader({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <div className="flex items-center justify-between border-b border-border px-4 py-3">
      <h2 className="text-xs font-semibold uppercase tracking-wider text-muted">{title}</h2>
      {action}
    </div>
  );
}
