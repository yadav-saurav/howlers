import { Users } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { Select } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { CommunityRole } from '@/types/api';
import { formatDate } from '@/utils/format';
import { useCommunityMembers, useSetMemberRole } from '../hooks';

const ASSIGNABLE: CommunityRole[] = ['MEMBER', 'CONTRIBUTOR', 'MODERATOR', 'ADMIN'];

/** Paginated member list. Owners/admins (`canManageRoles`) can change roles; the server re-checks. */
export function MembersList({ slug, canManageRoles }: { slug: string; canManageRoles: boolean }) {
  const [page, setPage] = useState(0);
  const q = useCommunityMembers(slug, page);
  const setRole = useSetMemberRole(slug);

  if (q.isPending) return <div className="space-y-3 p-4">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-10" />)}</div>;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data.items.length) return <EmptyState icon={Users} title="No members to show" />;

  return (
    <div>
      <ul className="divide-y divide-border">
        {q.data.items.map((m) => (
          <li key={m.user.id} className="flex items-center gap-3 px-4 py-2.5">
            <Avatar src={m.user.avatarUrl} name={m.user.username} size="sm" />
            <div className="min-w-0 flex-1">
              <Link to={`/u/${m.user.username}`} className="text-sm font-medium hover:underline">u/{m.user.username}</Link>
              <div className="text-xs text-faint">Joined {formatDate(m.joinedAt)}</div>
            </div>
            {canManageRoles && m.role !== 'OWNER' ? (
              <div className="w-36">
                <label className="sr-only" htmlFor={`role-${m.user.id}`}>Role for {m.user.username}</label>
                <Select
                  id={`role-${m.user.id}`}
                  className="h-8 text-xs"
                  value={m.role}
                  disabled={setRole.isPending}
                  onChange={(e) =>
                    setRole.mutate(
                      { username: m.user.username, role: e.target.value as CommunityRole },
                      {
                        onSuccess: () => toast.success(`Updated u/${m.user.username}`),
                        onError: (err) => toast.error(errorMessage(err)),
                      },
                    )
                  }
                >
                  {ASSIGNABLE.map((r) => <option key={r} value={r}>{r.charAt(0) + r.slice(1).toLowerCase()}</option>)}
                </Select>
              </div>
            ) : (
              <span className="text-[11px] uppercase tracking-wide text-faint">{m.role.toLowerCase()}</span>
            )}
          </li>
        ))}
      </ul>
      {q.data.totalPages > 1 && (
        <div className="flex items-center justify-between border-t border-border px-4 py-3">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
          <span className="text-xs text-muted">Page {page + 1} of {q.data.totalPages}</span>
          <Button variant="outline" size="sm" disabled={page + 1 >= q.data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</Button>
        </div>
      )}
    </div>
  );
}
