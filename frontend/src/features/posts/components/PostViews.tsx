import { Eye } from 'lucide-react';
import { cn, compactNumber } from '@/utils/format';

/** How many members opened the post. The API only sends it to the post's author, so only they ever see it. */
export function PostViews({ views, small = false }: { views?: number | null; small?: boolean }) {
  if (views == null) return null;
  const label = `${views} ${views === 1 ? 'view' : 'views'} (only you can see this)`;
  return (
    <span
      title="Views: only you can see this"
      aria-label={label}
      className={cn('inline-flex items-center gap-1 rounded-full text-muted', small ? 'h-7 px-2 text-xs' : 'h-9 px-3 text-sm')}
    >
      <Eye size={small ? 14 : 15} aria-hidden="true" /> {compactNumber(views)}
      {!small && <span aria-hidden="true" className="hidden sm:inline">{views === 1 ? 'view' : 'views'}</span>}
    </span>
  );
}
