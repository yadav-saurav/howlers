import { ArrowLeft, ShieldAlert } from 'lucide-react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useCommunity } from '@/features/communities/hooks';
import { useQueryClient } from '@tanstack/react-query';
import { modKeys, useModStats } from '@/features/moderation/api';
import { useTopic } from '@/features/realtime/RealtimeProvider';
import { AppealsTab, BansTab, LogTab } from '@/features/moderation/components/DashboardTabs';
import { QueueTab } from '@/features/moderation/components/QueueTab';
import { PeerEndorsements } from '@/features/reputation/components/PeerEndorsements';
import { HeldTab } from '@/features/moderation/components/HeldTab';
import { AutomodTab } from '@/features/automod/components/AutomodTab';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { ApiError } from '@/services/apiError';
import { cn } from '@/utils/format';

type Tab = 'queue' | 'held' | 'automod' | 'appeals' | 'bans' | 'log' | 'team';

function Tile({ label, value }: { label: string; value: number | undefined }) {
  return (
    <div className="rounded-xl bg-surface-2/60 px-4 py-3">
      <div className="text-xl font-bold tabular-nums">{value ?? '–'}</div>
      <div className="text-xs text-muted">{label}</div>
    </div>
  );
}

/** Community moderation dashboard: queue, appeals, bans and the audit log. Server enforces moderator rights. */
export default function ModPage() {
  const { slug = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'queue';
  const community = useCommunity(slug);
  const stats = useModStats(slug);
  useDocumentTitle(`Mod tools · c/${slug}`);
  const qc = useQueryClient();
  // Only moderators may subscribe; wait until the community says so (an unauthorized SUBSCRIBE is rejected).
  const modCommunityId = community.data?.viewer.canModerate ? community.data.id : null;
  useTopic(modCommunityId != null ? `/topic/modqueue.${modCommunityId}` : null, () => {
    void qc.invalidateQueries({ queryKey: modKeys.queue(slug) });
    void qc.invalidateQueries({ queryKey: [...modKeys.all(slug), 'held'] });
    void qc.invalidateQueries({ queryKey: modKeys.stats(slug) });
    void qc.invalidateQueries({ queryKey: [...modKeys.all(slug), 'automod', 'hits'] });
  });

  const forbidden = stats.error instanceof ApiError && stats.error.status === 403;
  if (forbidden || (community.data && !community.data.viewer.canModerate)) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-10">
        <EmptyState icon={ShieldAlert} title="Moderators only" description={`Only moderators of c/${slug} can open these tools.`} action={<Link to={`/c/${slug}`} className={buttonClasses('primary', 'sm')}>Back to c/{slug}</Link>} />
      </div>
    );
  }
  if (community.isError) return <div className="mx-auto max-w-3xl px-4 py-10"><ErrorState error={community.error} onRetry={() => community.refetch()} /></div>;

  const s = stats.data;
  const tabs: { v: Tab; label: string; badge?: number }[] = [
    { v: 'queue', label: 'Queue', badge: s?.openReports },
    { v: 'held', label: 'Held', badge: s?.heldForReview },
    { v: 'automod', label: 'Automod' },
    { v: 'appeals', label: 'Appeals', badge: s?.pendingAppeals },
    { v: 'bans', label: 'Bans' },
    { v: 'log', label: 'Log' },
    { v: 'team', label: 'Team' },
  ];

  return (
    <div className="mx-auto w-full max-w-4xl px-3 py-4 sm:px-6">
      <Link to={`/c/${slug}`} className="mb-3 inline-flex items-center gap-1.5 text-sm text-muted hover:text-text"><ArrowLeft size={15} /> c/{slug}</Link>
      <div className="mb-4 flex items-center gap-3">
        {community.data ? <Avatar src={community.data.iconUrl} name={slug} size="md" /> : <Skeleton className="h-9 w-9 rounded-full" />}
        <div>
          <h1 className="text-2xl font-bold">Mod tools</h1>
          <p className="text-sm text-muted">c/{slug}. Every action here is logged.</p>
        </div>
      </div>
      <div className="mb-5 grid grid-cols-2 gap-2 sm:grid-cols-5">
        <Tile label="Open reports" value={s?.openReports} />
        <Tile label="Pending appeals" value={s?.pendingAppeals} />
        <Tile label="Active bans" value={s?.activeBans} />
        <Tile label="Actions (30d)" value={s?.actionsLast30Days} />
        <Tile label="Removals (30d)" value={s?.removedLast30Days} />
      </div>
      <div role="tablist" aria-label="Moderation sections" className="mb-4 flex gap-1 overflow-x-auto">
        {tabs.map((t) => (
          <button key={t.v} role="tab" type="button" aria-selected={tab === t.v} onClick={() => setParams({ tab: t.v }, { replace: true })}
            className={cn('inline-flex h-9 items-center gap-1.5 rounded-full px-4 text-sm font-semibold', tab === t.v ? 'bg-surface-3 text-text' : 'text-muted hover:bg-surface-2')}>
            {t.label}
            {!!t.badge && <span className="rounded-full bg-danger px-1.5 text-[11px] font-bold text-danger-fg">{t.badge}</span>}
          </button>
        ))}
      </div>
      <div role="tabpanel">
        {tab === 'queue' && <QueueTab slug={slug} />}
        {tab === 'held' && <HeldTab slug={slug} />}
        {tab === 'automod' && <AutomodTab slug={slug} />}
        {tab === 'appeals' && <AppealsTab slug={slug} />}
        {tab === 'bans' && <BansTab slug={slug} />}
        {tab === 'log' && <LogTab slug={slug} />}
        {tab === 'team' && <PeerEndorsements slug={slug} />}
      </div>
    </div>
  );
}
