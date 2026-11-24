import { Ban, Gavel, ScrollText, UserX } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Badge, Skeleton } from '@/components/ui/Display';
import { Textarea } from '@/components/ui/Field';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { Appeal } from '@/types/moderation';
import { cn, timeAgo } from '@/utils/format';
import { modApi, useAppeals, useModBans, useModLog, useModMutation } from '../api';
import { formatBanExpiry } from '../helpers';
import { BanDialog } from './BanDialog';
import { ModActionLine } from './ModActionLine';

const list = 'divide-y divide-border rounded-2xl border border-border';

function AppealCard({ slug, a }: { slug: string; a: Appeal }) {
  const [response, setResponse] = useState('');
  const resolve = useModMutation(slug, (accept: boolean) => modApi.resolveAppeal(a.id, { accept, response: response.trim() || undefined }));
  const done = (accept: boolean) =>
    resolve.mutate(accept, { onSuccess: () => toast.success(accept ? 'Appeal accepted and action reversed' : 'Appeal rejected'), onError: (e) => toast.error(errorMessage(e)) });
  return (
    <div className="space-y-2 p-4">
      <div className="flex items-center gap-2 text-sm">
        <Avatar src={a.user.avatarUrl} name={a.user.username} size="xs" />
        <Link to={`/u/${a.user.username}`} className="font-semibold hover:underline">u/{a.user.username}</Link>
        <span className="text-xs text-faint">appealed {timeAgo(a.createdAt)}</span>
        <Badge tone={a.status === 'PENDING' ? 'warning' : a.status === 'ACCEPTED' ? 'accent' : 'neutral'} className="ml-auto rounded-full px-2">{a.status.toLowerCase()}</Badge>
      </div>
      <div className="rounded-xl bg-surface-2/60 p-3"><ModActionLine a={a.action} slug={slug} /></div>
      <p className="whitespace-pre-wrap text-sm">“{a.message}”</p>
      {a.status === 'PENDING' ? (
        <>
          <Textarea value={response} onChange={(e) => setResponse(e.target.value)} rows={2} maxLength={1000} placeholder="Optional response to the user" aria-label="Response" />
          <div className="flex gap-2">
            <Button size="sm" loading={resolve.isPending} onClick={() => done(true)}>Accept and reverse</Button>
            <Button size="sm" variant="secondary" loading={resolve.isPending} onClick={() => done(false)}>Reject</Button>
          </div>
        </>
      ) : (
        a.response && <p className="text-xs text-muted">Response: “{a.response}”</p>
      )}
    </div>
  );
}

export function AppealsTab({ slug }: { slug: string }) {
  const [status, setStatus] = useState<'PENDING' | 'ALL'>('PENDING');
  const q = useAppeals(slug, status);
  return (
    <div className="space-y-3">
      <div className="flex gap-1 rounded-full bg-surface-2 p-1 text-xs font-semibold" role="group" aria-label="Appeal filter">
        {(['PENDING', 'ALL'] as const).map((s) => (
          <button key={s} type="button" aria-pressed={status === s} onClick={() => setStatus(s)} className={cn('rounded-full px-3 py-1.5', status === s ? 'bg-surface-3 text-text' : 'text-muted')}>
            {s === 'PENDING' ? 'Pending' : 'All'}
          </button>
        ))}
      </div>
      {q.isPending && <Skeleton className="h-40 rounded-2xl" />}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data?.length === 0 && <EmptyState icon={Gavel} title={status === 'PENDING' ? 'No pending appeals' : 'No appeals yet'} description="When someone appeals a removal or ban, it shows up here." />}
      {!!q.data?.length && <div className={list}>{q.data.map((a) => <AppealCard key={a.id} slug={slug} a={a} />)}</div>}
    </div>
  );
}

export function BansTab({ slug }: { slug: string }) {
  const q = useModBans(slug);
  const [open, setOpen] = useState(false);
  const unban = useModMutation(slug, (username: string) => modApi.unban(slug, username));
  return (
    <div className="space-y-3">
      <div className="flex justify-end"><Button size="sm" variant="danger" onClick={() => setOpen(true)}><Ban size={14} /> Ban a user</Button></div>
      {q.isPending && <Skeleton className="h-32 rounded-2xl" />}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data?.length === 0 && <EmptyState icon={UserX} title="No active bans" description="Banned users can read but not participate." />}
      {!!q.data?.length && (
        <ul className={list}>
          {q.data.map((b) => (
            <li key={b.user.id} className="flex flex-wrap items-center gap-3 p-4">
              <Avatar src={b.user.avatarUrl} name={b.user.username} size="sm" />
              <div className="min-w-0 flex-1">
                <Link to={`/u/${b.user.username}`} className="text-sm font-semibold hover:underline">u/{b.user.username}</Link>
                <p className="text-xs text-muted">Banned {formatBanExpiry(b.expiresAt)} · “{b.reason}”</p>
                <p className="text-xs text-faint">{b.bannedBy ? `by u/${b.bannedBy.username}` : ''} {timeAgo(b.createdAt)}</p>
              </div>
              <Button size="sm" variant="secondary" loading={unban.isPending && unban.variables === b.user.username}
                onClick={() => unban.mutate(b.user.username, { onSuccess: () => toast.success(`Unbanned u/${b.user.username}`), onError: (e) => toast.error(errorMessage(e)) })}>
                Unban
              </Button>
            </li>
          ))}
        </ul>
      )}
      {open && <BanDialog slug={slug} open={open} onOpenChange={setOpen} />}
    </div>
  );
}

export function LogTab({ slug }: { slug: string }) {
  const q = useModLog(slug);
  if (q.isPending) return <Skeleton className="h-60 rounded-2xl" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const items = q.data.pages.flatMap((p) => p.items);
  if (!items.length) return <EmptyState icon={ScrollText} title="No moderation actions yet" description="Every removal, approval, lock, pin and ban is recorded here." />;
  return (
    <>
      <ul className={list}>{items.map((a) => <li key={a.id} className="p-4"><ModActionLine a={a} slug={slug} /></li>)}</ul>
      <LoadMore hasMore={!!q.hasNextPage} loading={q.isFetchingNextPage} onLoad={() => q.fetchNextPage()} />
    </>
  );
}
