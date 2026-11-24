import { UserX } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { Dialog } from '@/components/ui/Dialog';
import { ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { useBlockUser, useBlocks } from '../api';

/** Block/unblock button for a profile. Blocking asks for confirmation; unblocking doesn't. */
export function BlockButton({ username, isBlocked }: { username: string; isBlocked: boolean }) {
  const [confirm, setConfirm] = useState(false);
  const m = useBlockUser(username);
  const run = (blocked: boolean) =>
    m.mutate(blocked, {
      onSuccess: () => {
        setConfirm(false);
        toast.success(blocked ? `Blocked u/${username}` : `Unblocked u/${username}`);
      },
      onError: (e) => toast.error(errorMessage(e)),
    });
  return (
    <>
      <Button size="sm" variant={isBlocked ? 'secondary' : 'ghost'} loading={m.isPending && !confirm} onClick={() => (isBlocked ? run(false) : setConfirm(true))}>
        <UserX size={15} /> {isBlocked ? 'Unblock' : 'Block'}
      </Button>
      <Dialog
        open={confirm}
        onOpenChange={setConfirm}
        size="sm"
        title={`Block u/${username}?`}
        description="Their posts disappear from your feeds and search, their comments are hidden from you, and they can't notify you. They aren't told."
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirm(false)}>Cancel</Button>
            <Button variant="danger" loading={m.isPending} onClick={() => run(true)}>Block</Button>
          </>
        }
      >
        <span className="sr-only">Confirm block</span>
      </Dialog>
    </>
  );
}

function UnblockRow({ username, avatarUrl }: { username: string; avatarUrl: string | null }) {
  const m = useBlockUser(username);
  return (
    <li className="flex items-center gap-3 px-4 py-2.5">
      <Avatar src={avatarUrl} name={username} size="sm" />
      <Link to={`/u/${username}`} className="flex-1 text-sm hover:underline">u/{username}</Link>
      <Button size="sm" variant="secondary" loading={m.isPending}
        onClick={() => m.mutate(false, { onSuccess: () => toast.success(`Unblocked u/${username}`), onError: (e) => toast.error(errorMessage(e)) })}>
        Unblock
      </Button>
    </li>
  );
}

export function BlockedUsersCard() {
  const q = useBlocks();
  return (
    <Card>
      <CardHeader title="Blocked users" />
      {q.isPending && <div className="p-4"><Skeleton className="h-8" /></div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data?.length === 0 && <p className="px-4 py-3 text-sm text-muted">You haven't blocked anyone. You can block someone from their profile.</p>}
      {!!q.data?.length && <ul className="divide-y divide-border">{q.data.map((u) => <UnblockRow key={u.id} username={u.username} avatarUrl={u.avatarUrl} />)}</ul>}
    </Card>
  );
}
