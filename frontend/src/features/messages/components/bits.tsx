import { Users } from 'lucide-react';
import { useRealtimeStatus } from '@/features/realtime/RealtimeProvider';
import { cn } from '@/utils/format';

export function OnlineDot({ className }: { className?: string }) {
  return (
    <span className={cn('block h-3 w-3 rounded-full border-2 border-surface bg-accent', className)} role="img" aria-label="Online" title="Online" />
  );
}

/** A pack room's avatar: its icon emoji on a soft tile, or a "group" glyph without one. */
export function RoomAvatar({ icon, size = 'md' }: { icon: string | null; size?: 'sm' | 'md' }) {
  return (
    <span aria-hidden="true" className={cn('flex shrink-0 items-center justify-center rounded-xl bg-accent/15', size === 'md' ? 'h-9 w-9 text-lg' : 'h-7 w-7 text-sm')}>
      {icon ?? <Users size={size === 'md' ? 18 : 14} className="text-accent" />}
    </span>
  );
}

/** Small, unobtrusive hint when the live connection isn't up. Hidden when connected. */
export function ConnectionHint({ className }: { className?: string }) {
  const status = useRealtimeStatus();
  if (status === 'connected' || status === 'idle') return null;
  return (
    <span role="status" className={cn('inline-flex items-center gap-1.5 text-xs text-muted', className)}>
      <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-warning" aria-hidden="true" />
      {status === 'connecting' ? 'Connecting…' : 'Reconnecting…'}
    </span>
  );
}
