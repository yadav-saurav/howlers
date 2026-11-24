import { cn } from '@/utils/format';
import { ROOM_ICONS } from '../rooms';

/** Radio group of room icons (plus "none"). Native radios keep arrow-key navigation and screen-reader semantics. */
export function IconChoice({ value, onChange }: { value: string | null; onChange: (v: string | null) => void }) {
  const options: (string | null)[] = [...ROOM_ICONS, null];
  return (
    <fieldset>
      <legend className="mb-1.5 text-sm font-medium">Icon <span className="text-xs font-normal text-faint">optional</span></legend>
      <div className="flex flex-wrap gap-1.5">
        {options.map((o) => (
          <label key={o ?? 'none'} className={cn(
            'flex h-9 min-w-9 cursor-pointer items-center justify-center rounded-lg border px-1.5 text-lg has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-accent',
            value === o ? 'border-accent bg-accent/15' : 'border-border hover:bg-surface-2',
          )}>
            <input type="radio" name="room-icon" className="sr-only" checked={value === o} onChange={() => onChange(o)}
              aria-label={o ? `Icon ${o}` : 'No icon'} />
            {o ?? <span className="text-xs text-muted">None</span>}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
