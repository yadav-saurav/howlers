import { X } from 'lucide-react';
import { useState } from 'react';
import { Avatar } from '@/components/ui/Display';
import { Input } from '@/components/ui/Field';
import { Spinner } from '@/components/ui/Spinner';
import { useSuggest } from '@/features/search/hooks';
import { useAuthStore } from '@/store/authStore';
import { displayName } from '@/utils/format';
import { parseUsernames } from '../rooms';

/**
 * Pick people by username for a pack room: type to search (suggestions from site search), Enter adds the top match or
 * what you typed (comma- or space-separated names work too). Chosen people show as removable chips. Whether someone
 * can actually be added (blocks, their message settings) is decided by the server when you submit.
 */
export function PeoplePicker({ value, onChange, exclude = [], label = 'Add people', inputId }: {
  value: string[];
  onChange: (v: string[]) => void;
  /** Usernames already in the room. */
  exclude?: string[];
  label?: string;
  inputId?: string;
}) {
  const [q, setQ] = useState('');
  const suggest = useSuggest(q);
  const me = useAuthStore((s) => s.user?.username ?? '');
  const taken = new Set([...value, ...exclude, me].map((u) => u.toLowerCase()));
  const users = (suggest.data?.users ?? []).filter((u) => !taken.has(u.username.toLowerCase()));

  const add = (names: string[]) => {
    const next = [...value];
    for (const n of names) if (!taken.has(n.toLowerCase()) && !next.some((x) => x.toLowerCase() === n.toLowerCase())) next.push(n);
    onChange(next);
    setQ('');
  };

  return (
    <div className="space-y-2">
      {value.length > 0 && (
        <ul className="flex flex-wrap gap-1.5" aria-label="People to add">
          {value.map((u) => (
            <li key={u} className="inline-flex items-center gap-1 rounded-full bg-surface-2 py-0.5 pl-2.5 pr-1 text-sm">
              u/{u}
              <button type="button" aria-label={`Remove u/${u}`} onClick={() => onChange(value.filter((x) => x !== u))}
                className="rounded-full p-0.5 text-muted hover:bg-surface-3 hover:text-text">
                <X size={13} />
              </button>
            </li>
          ))}
        </ul>
      )}
      <Input
        id={inputId}
        value={q}
        onChange={(e) => {
          const v = e.target.value;
          // Pasting "a, b c" adds them all at once.
          if (/[,\s]/.test(v) && parseUsernames(v).length > 1) add(parseUsernames(v));
          else setQ(v);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            if (q.trim()) add([users[0]?.username ?? q.trim().replace(/^u\//, '')]);
          } else if (e.key === 'Backspace' && !q && value.length) {
            onChange(value.slice(0, -1));
          }
        }}
        placeholder="Search by username"
        aria-label={label}
        autoComplete="off"
      />
      {q.trim().length >= 2 && (
        <ul className="max-h-48 overflow-y-auto rounded-md border border-border" aria-label="Matching people">
          {suggest.isFetching && users.length === 0 && (
            <li className="flex items-center gap-2 px-2 py-2 text-sm text-muted"><Spinner size={14} /> Searching…</li>
          )}
          {users.map((u) => (
            <li key={u.id}>
              <button type="button" onClick={() => add([u.username])}
                className="flex w-full items-center gap-3 px-2 py-1.5 text-left hover:bg-surface-2">
                <Avatar src={u.avatarUrl} name={u.username} size="sm" />
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium">{displayName(u)}</span>
                  <span className="block truncate text-xs text-muted">u/{u.username}</span>
                </span>
              </button>
            </li>
          ))}
          {!suggest.isFetching && users.length === 0 && (
            <li className="px-2 py-2 text-sm text-muted">No matches. Press Enter to add u/{q.trim().replace(/^u\//, '')} anyway.</li>
          )}
        </ul>
      )}
    </div>
  );
}
