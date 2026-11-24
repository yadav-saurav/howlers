import { Search, ShieldCheck, ShieldOff, UserCheck, UserX, Users } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Badge, Skeleton } from '@/components/ui/Display';
import { Dialog } from '@/components/ui/Dialog';
import { Input, Select } from '@/components/ui/Field';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { errorMessage } from '@/services/apiError';
import type { AdminUser } from '@/types/admin';
import { formatDate, timeAgo } from '@/utils/format';
import { adminApi, useAdminMutation, useAdminUser, useAdminUsers } from '../api';
import { describeAudit, userActions, type UserAction } from '../helpers';
import { ActionDialog } from './ActionDialog';

function StatusBadges({ u }: { u: Pick<AdminUser, 'role' | 'status'> }) {
  return (
    <>
      {u.role === 'ADMIN' && <Badge tone="info" className="rounded-full px-2">Admin</Badge>}
      {u.status === 'SUSPENDED' && <Badge tone="danger" className="rounded-full px-2">Suspended</Badge>}
      {u.status === 'DEACTIVATED' && <Badge className="rounded-full px-2">Deactivated</Badge>}
      {u.status === 'DELETED' && <Badge className="rounded-full px-2">Deleted</Badge>}
    </>
  );
}

const ACTION_COPY: Record<UserAction, { label: string; title: string; description: string; required: boolean; danger: boolean }> = {
  suspend: {
    label: 'Suspend',
    title: 'Suspend this account?',
    description: 'They are signed out everywhere at once and can’t sign in again until you lift the suspension. Their content stays up.',
    required: true,
    danger: true,
  },
  unsuspend: { label: 'Lift suspension', title: 'Lift the suspension?', description: 'They can sign in again straight away.', required: false, danger: false },
  promote: {
    label: 'Make admin',
    title: 'Give admin rights?',
    description: 'Admins can open this dashboard, suspend accounts, quarantine or ban communities and moderate everywhere.',
    required: false,
    danger: false,
  },
  demote: { label: 'Remove admin', title: 'Remove admin rights?', description: 'Takes effect on their next request.', required: false, danger: true },
};

function UserDetail({ id, onClose }: { id: number; onClose: () => void }) {
  const { user: me } = useAuth();
  const q = useAdminUser(id);
  const [pending, setPending] = useState<UserAction | null>(null);
  const act = useAdminMutation(({ action, reason }: { action: UserAction; reason: string }) => {
    const r = reason || undefined;
    switch (action) {
      case 'suspend': return adminApi.suspend(id, reason);
      case 'unsuspend': return adminApi.unsuspend(id, r);
      case 'promote': return adminApi.setRole(id, 'ADMIN', r);
      case 'demote': return adminApi.setRole(id, 'USER', r);
    }
  });
  const d = q.data;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={d ? `u/${d.user.username}` : 'Account'} size="lg">
      {q.isPending && <Skeleton className="h-40" />}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {d && (
        <div className="space-y-4 text-sm">
          <div className="flex flex-wrap items-center gap-3">
            <Avatar src={d.user.avatarUrl} name={d.user.username} size="md" />
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-1.5">
                <Link to={`/u/${d.user.username}`} className="font-semibold hover:underline" onClick={onClose}>u/{d.user.username}</Link>
                <StatusBadges u={d.user} />
              </div>
              <p className="truncate text-xs text-muted">{d.user.email} · {d.user.emailVerified ? 'verified' : 'unverified'} · joined {formatDate(d.user.createdAt)}</p>
            </div>
          </div>
          <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {[
              ['Posts', d.user.postCount],
              ['Comments', d.user.commentCount],
              ['Post karma', d.user.postKarma],
              ['Comment karma', d.user.commentKarma],
            ].map(([k, v]) => (
              <div key={k} className="rounded-xl bg-surface-2/60 px-3 py-2">
                <dt className="text-xs text-muted">{k}</dt>
                <dd className="font-semibold tabular-nums">{v}</dd>
              </div>
            ))}
          </dl>
          {d.user.status === 'SUSPENDED' && (
            <p className="rounded-xl border border-danger/30 bg-danger/10 px-3 py-2 text-danger">
              Suspended {d.user.suspendedAt ? timeAgo(d.user.suspendedAt) : ''}: “{d.user.suspendedReason}”
            </p>
          )}
          {d.moderatorOf.length > 0 && (
            <p className="text-xs text-muted">Moderates {d.moderatorOf.map((c) => `c/${c.slug}`).join(', ')}</p>
          )}
          <div className="flex flex-wrap gap-2">
            {userActions(d.user, me?.id).map((a) => {
              const c = ACTION_COPY[a];
              const Icon = a === 'suspend' ? UserX : a === 'unsuspend' ? UserCheck : a === 'promote' ? ShieldCheck : ShieldOff;
              return (
                <Button key={a} size="sm" variant={c.danger ? 'danger' : 'secondary'} onClick={() => setPending(a)}>
                  <Icon size={14} /> {c.label}
                </Button>
              );
            })}
            {me?.id === d.user.id && <p className="text-xs text-faint">You can’t change your own account here.</p>}
          </div>
          <section>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-faint">Admin history</h3>
            {d.history.length === 0 ? (
              <p className="text-xs text-muted">No admin actions on this account.</p>
            ) : (
              <ul className="space-y-1 text-xs text-muted">
                {d.history.map((h) => (
                  <li key={h.id}>
                    {h.admin ? `u/${h.admin.username}` : 'An admin'} {describeAudit(h)} {timeAgo(h.createdAt)}
                    {h.reason && <> · “{h.reason}”</>}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
      {pending && (
        <ActionDialog
          open
          onOpenChange={(o) => !o && setPending(null)}
          title={ACTION_COPY[pending].title}
          description={ACTION_COPY[pending].description}
          confirmLabel={ACTION_COPY[pending].label}
          variant={ACTION_COPY[pending].danger ? 'danger' : 'primary'}
          reasonRequired={ACTION_COPY[pending].required}
          loading={act.isPending}
          onConfirm={(reason) =>
            act.mutate(
              { action: pending, reason },
              { onSuccess: () => { toast.success('Done. Recorded in the audit log.'); setPending(null); }, onError: (e) => toast.error(errorMessage(e)) },
            )
          }
        />
      )}
    </Dialog>
  );
}

export function UsersTab() {
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const debounced = useDebouncedValue(q.trim(), 300);
  const list = useAdminUsers(debounced, status);
  const [open, setOpen] = useState<number | null>(null);
  const items = list.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <div className="space-y-3">
      <div className="flex flex-col gap-2 sm:flex-row">
        <div className="relative flex-1">
          <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" aria-hidden="true" />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Username or email starts with…" aria-label="Search accounts" className="pl-9" />
        </div>
        <Select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Filter accounts" className="sm:w-44">
          <option value="">All accounts</option>
          <option value="ACTIVE">Active</option>
          <option value="SUSPENDED">Suspended</option>
          <option value="ADMIN">Admins</option>
          <option value="DEACTIVATED">Deactivated</option>
        </Select>
      </div>
      {list.isPending && <Skeleton className="h-64 rounded-2xl" />}
      {list.isError && <ErrorState error={list.error} onRetry={() => list.refetch()} />}
      {list.isSuccess && items.length === 0 && <EmptyState icon={Users} title="No matching accounts" />}
      {items.length > 0 && (
        <ul className="divide-y divide-border rounded-2xl border border-border">
          {items.map((u) => (
            <li key={u.id}>
              <button type="button" onClick={() => setOpen(u.id)} className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-surface-2/50">
                <Avatar src={u.avatarUrl} name={u.username} size="sm" />
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-1.5 text-sm font-semibold">u/{u.username} <StatusBadges u={u} /></span>
                  <span className="block truncate text-xs text-muted">{u.email} · joined {timeAgo(u.createdAt)}</span>
                </span>
                <span className="hidden shrink-0 text-right text-xs text-muted sm:block">
                  {u.postCount} posts · {u.commentCount} comments
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onLoad={() => list.fetchNextPage()} />
      {open != null && <UserDetail id={open} onClose={() => setOpen(null)} />}
    </div>
  );
}
