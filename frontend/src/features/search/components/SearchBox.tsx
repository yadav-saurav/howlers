import { FileText, Search, Users } from 'lucide-react';
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Avatar } from '@/components/ui/Display';
import { Spinner } from '@/components/ui/Spinner';
import { cn, displayName } from '@/utils/format';
import { useSuggest } from '../hooks';

interface Option {
  key: string;
  label: string;
  sub?: string;
  to: string;
  kind: 'community' | 'user' | 'post' | 'query';
  iconUrl?: string | null;
}

/** Accessible combobox: type to get suggestions, arrows to move, Enter to open, Esc to close. */
export function SearchBox({ className, onDone }: { className?: string; onDone?: () => void }) {
  const [params] = useSearchParams();
  const [value, setValue] = useState(params.get('q') ?? '');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const navigate = useNavigate();
  const listId = useId();
  const wrap = useRef<HTMLDivElement>(null);
  const suggest = useSuggest(value);

  const options = useMemo<Option[]>(() => {
    const q = value.trim();
    if (!q) return [];
    const out: Option[] = [{ key: 'q', label: `Search for “${q}”`, to: `/search?q=${encodeURIComponent(q)}`, kind: 'query' }];
    const d = suggest.data;
    if (d) {
      d.communities.forEach((c) =>
        out.push({ key: `c${c.id}`, label: `c/${c.slug}`, sub: c.name, to: `/c/${c.slug}`, kind: 'community', iconUrl: c.iconUrl }),
      );
      d.users.forEach((u) =>
        out.push({ key: `u${u.id}`, label: `u/${u.username}`, sub: displayName(u), to: `/u/${u.username}`, kind: 'user', iconUrl: u.avatarUrl }),
      );
      d.posts.forEach((p) =>
        out.push({ key: `p${p.id}`, label: p.title, sub: `c/${p.community.slug}`, to: `/c/${p.community.slug}/p/${p.id}`, kind: 'post' }),
      );
    }
    return out;
  }, [value, suggest.data]);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (wrap.current && !wrap.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, []);

  const go = (to: string) => {
    setOpen(false);
    setActive(-1);
    navigate(to);
    onDone?.();
  };

  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((a) => Math.min(options.length - 1, a + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(-1, a - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const opt = options[active];
      if (opt) go(opt.to);
      else if (value.trim()) go(`/search?q=${encodeURIComponent(value.trim())}`);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  const show = open && options.length > 0;
  return (
    <div ref={wrap} className={cn('relative', className)}>
      <label htmlFor={`${listId}-input`} className="sr-only">
        Search Howlers
      </label>
      <Search size={18} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
      <input
        id={`${listId}-input`}
        type="search"
        role="combobox"
        aria-expanded={show}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
        autoComplete="off"
        placeholder="Search Howlers"
        value={value}
        onChange={(e) => {
          setValue(e.target.value);
          setOpen(true);
          setActive(-1);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKey}
        className="h-10 w-full rounded-full border border-transparent bg-surface-2 pl-10 pr-9 text-sm placeholder:text-muted hover:bg-surface-3 focus:border-brand/60 focus:bg-surface focus:outline-none focus:ring-2 focus:ring-brand/20"
      />
      {suggest.isFetching && <Spinner size={14} className="absolute right-3 top-1/2 -translate-y-1/2 text-faint" />}
      {show && (
        <ul
          id={listId}
          role="listbox"
          className="absolute left-0 right-0 top-[calc(100%+6px)] z-40 max-h-[60vh] overflow-y-auto rounded-lg border border-border bg-surface p-1 shadow-float"
        >
          {options.map((o, i) => (
            // eslint-disable-next-line jsx-a11y/click-events-have-key-events -- combobox pattern: arrow keys and Enter are handled on the input (aria-activedescendant)
            <li
              key={o.key}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === active}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => go(o.to)}
              onMouseEnter={() => setActive(i)}
              className={cn(
                'flex cursor-pointer items-center gap-2.5 rounded-md px-2.5 py-2 text-sm',
                i === active && 'bg-surface-2',
              )}
            >
              {o.kind === 'query' && <Search size={15} className="text-faint" />}
              {o.kind === 'post' && <FileText size={15} className="text-faint" />}
              {o.kind === 'community' && <Avatar src={o.iconUrl} name={o.label} size="xs" square />}
              {o.kind === 'user' && (o.iconUrl ? <Avatar src={o.iconUrl} name={o.label} size="xs" /> : <Users size={15} className="text-faint" />)}
              <span className="min-w-0 flex-1 truncate">{o.label}</span>
              {o.sub && <span className="hidden truncate text-xs text-faint sm:inline">{o.sub}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
