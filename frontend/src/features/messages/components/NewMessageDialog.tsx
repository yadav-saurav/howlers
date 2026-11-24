import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Dialog } from '@/components/ui/Dialog';
import { Avatar } from '@/components/ui/Display';
import { Input } from '@/components/ui/Field';
import { Spinner } from '@/components/ui/Spinner';
import { useSuggest } from '@/features/search/hooks';
import { errorMessage } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';
import { displayName } from '@/utils/format';
import { useOpenConversation } from '../api';

/** Pick a person by username (autocomplete from search suggestions) and open the conversation. */
export function NewMessageDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const [q, setQ] = useState('');
  const [error, setError] = useState<string | null>(null);
  const suggest = useSuggest(q);
  const meId = useAuthStore((s) => s.user?.id);
  const start = useOpenConversation();
  const navigate = useNavigate();
  const users = (suggest.data?.users ?? []).filter((u) => u.id !== meId);

  const go = (username: string) => {
    setError(null);
    start.mutate(username.replace(/^u\//, '').trim(), {
      onSuccess: (c) => {
        onOpenChange(false);
        setQ('');
        navigate(`/messages/${c.id}`);
      },
      onError: (e) => setError(errorMessage(e)),
    });
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        onOpenChange(o);
        if (!o) setError(null);
      }}
      title="New message"
      description="Search for someone by username."
    >
      <form
        className="space-y-3 p-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (q.trim()) go(users[0]?.username ?? q);
        }}
      >
        {/* eslint-disable-next-line jsx-a11y/no-autofocus -- the dialog exists to type a username; focus belongs here */}
        <Input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="username" aria-label="Username" autoComplete="off" />
        {error && <p role="alert" className="text-sm text-danger">{error}</p>}
        <ul className="max-h-72 overflow-y-auto" aria-label="Matching people">
          {suggest.isFetching && users.length === 0 && (
            <li className="flex items-center gap-2 px-2 py-2 text-sm text-muted"><Spinner size={14} /> Searching…</li>
          )}
          {users.map((u) => (
            <li key={u.id}>
              <button
                type="button"
                disabled={start.isPending}
                onClick={() => go(u.username)}
                className="flex w-full items-center gap-3 rounded-md px-2 py-2 text-left hover:bg-surface-2 disabled:opacity-60"
              >
                <Avatar src={u.avatarUrl} name={u.username} size="sm" />
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium">{displayName(u)}</span>
                  <span className="block truncate text-xs text-muted">u/{u.username}</span>
                </span>
              </button>
            </li>
          ))}
          {q.trim().length >= 2 && !suggest.isFetching && users.length === 0 && (
            <li className="px-2 py-2 text-sm text-muted">No people match “{q.trim()}”.</li>
          )}
        </ul>
      </form>
    </Dialog>
  );
}
