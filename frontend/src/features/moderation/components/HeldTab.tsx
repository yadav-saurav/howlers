import { ShieldCheck } from 'lucide-react';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { heldApi, useHeld } from '@/features/admin/api';
import { SpamFlagCard } from '@/features/admin/components/SpamFlagCard';
import { errorMessage } from '@/services/apiError';
import { useModMutation } from '../api';

/** Content the spam heuristics held in this community. Only its author and moderators can see it until approved. */
export function HeldTab({ slug }: { slug: string }) {
  const q = useHeld(slug);
  const resolve = useModMutation(slug, ({ id, verdict }: { id: number; verdict: 'approve' | 'remove' }) => heldApi.resolve(id, verdict));
  if (q.isPending) return <div className="space-y-3">{Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-40 rounded-2xl" />)}</div>;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data.length) {
    return <EmptyState icon={ShieldCheck} title="Nothing held for review" description="Posts and comments held by the spam checks appear here. Only their authors and moderators can see them." mascot="sleepy" />;
  }
  return (
    <div className="space-y-3">
      <p className="text-xs text-muted">
        Held by automatic checks (link bursts, duplicates, posting bursts, mass mentions) or this community’s automod rules. Approving publishes the item and sends its notifications;
        removing keeps it hidden and tells the author, who can appeal.
      </p>
      {q.data.map((f) => (
        <SpamFlagCard
          key={f.id}
          flag={f}
          busy={resolve.isPending && resolve.variables?.id === f.id}
          onResolve={(verdict) =>
            resolve.mutate(
              { id: f.id, verdict },
              { onSuccess: () => toast.success(verdict === 'approve' ? 'Approved and published' : 'Removed'), onError: (e) => toast.error(errorMessage(e)) },
            )
          }
        />
      ))}
    </div>
  );
}
