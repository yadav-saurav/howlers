import { Award, ChevronDown, ShieldCheck, Trophy } from 'lucide-react';
import { useId, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Avatar, Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { Select } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Segmented } from '@/components/ui/Tabs';
import { useMyCommunities } from '@/features/communities/hooks';
import { useModLeaderboard, useReputationOverview } from '@/features/reputation/api';
import { formatPoints, periodLabel } from '@/features/reputation/logic';
import { FormulaPanel, ModBadges, ScoreBreakdown } from '@/features/reputation/components/ScoreBreakdown';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import type { ModeratorScore, ScoreFormula } from '@/types/recognition';
import { timeAgo } from '@/utils/format';

type Tab = 'moderators' | 'topics';

/**
 * One ranking row. The links stay plain links and a separate disclosure button opens the breakdown
 * (links inside a <summary> would be interactive controls nested in another control).
 */
function ModeratorRow({ s, formula }: { s: ModeratorScore; formula: ScoreFormula }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  return (
    <li>
      <div className="flex items-center gap-3 px-4 py-3">
        <span className="w-7 shrink-0 text-center text-sm font-bold tabular-nums text-muted">#{s.rank}</span>
        <Avatar src={s.moderator.avatarUrl} name={s.moderator.username} size="md" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 text-sm">
            {/* min-h-6 keeps both links at least 24 px tall, the WCAG 2.2 minimum touch target. */}
            <Link to={`/u/${s.moderator.username}`} className="inline-flex min-h-6 items-center font-semibold hover:underline">
              u/{s.moderator.username}
            </Link>
            <Link to={`/c/${s.community.slug}`} className="inline-flex min-h-6 items-center text-xs text-muted hover:underline">
              c/{s.community.slug}
            </Link>
          </div>
          <div className="mt-1"><ModBadges badges={s.badges} /></div>
        </div>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((o) => !o)}
          className="flex shrink-0 items-center gap-2 rounded-lg px-2 py-1 text-right hover:bg-surface-2/60"
        >
          <span>
            <span className="sr-only">Score breakdown for u/{s.moderator.username}: </span>
            <span className="block text-lg font-bold tabular-nums">{s.score.toFixed(1)}</span>{' '}
            <span className="block text-[11px] text-muted">of 100</span>
          </span>
          <ChevronDown size={16} className={`shrink-0 text-muted transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
        </button>
      </div>
      <div id={panelId} hidden={!open} className="border-t border-border bg-surface-2/30 px-4 py-3">
        {open && <ScoreBreakdown score={s} formula={formula} />}
      </div>
    </li>
  );
}

function ModeratorsTab() {
  const [params, setParams] = useSearchParams();
  const period = params.get('period') ?? '';
  const community = params.get('community') ?? '';
  const q = useModLeaderboard(period, community);
  const global = useModLeaderboard(period, '');
  const mine = useMyCommunities();
  const set = (k: string, v: string) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v); else next.delete(k);
    setParams(next, { replace: true });
  };

  const scopes = useMemo(() => {
    const s = new Map<string, string>();
    for (const e of global.data?.entries ?? []) s.set(e.community.slug, e.community.name);
    for (const c of mine.data ?? []) s.set(c.slug, c.name);
    if (community && !s.has(community)) s.set(community, community);
    return [...s.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [global.data, mine.data, community]);

  const d = q.data;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2 px-4 sm:px-0">
        <label className="sr-only" htmlFor="lb-period">Period</label>
        <Select id="lb-period" className="w-auto min-w-[170px]" value={d?.period ?? period} onChange={(e) => set('period', e.target.value)}>
          {(d?.periods ?? (period ? [period] : [])).map((p) => <option key={p} value={p}>{periodLabel(p)}</option>)}
        </Select>
        <label className="sr-only" htmlFor="lb-scope">Community</label>
        <Select id="lb-scope" className="w-auto min-w-[190px]" value={community} onChange={(e) => set('community', e.target.value)}>
          <option value="">All public communities</option>
          {scopes.map(([slug]) => <option key={slug} value={slug}>c/{slug}</option>)}
        </Select>
      </div>
      <Card className="rounded-none border-x-0 sm:rounded-lg sm:border-x">
        {q.isPending ? (
          <div className="space-y-3 p-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-12" />)}</div>
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        ) : d!.entries.length === 0 ? (
          <EmptyState
            icon={ShieldCheck}
            title={`No ranked moderators for ${periodLabel(d!.period)}`}
            description={`Moderators appear once they have at least ${d!.formula.minActivity} removals, bans or handled reports in the month. Scores are computed from real moderation activity only, so a quiet month stays empty.`}
            mascot="sleepy"
          />
        ) : (
          <ol className="divide-y divide-border" aria-label={`Moderator ranking for ${periodLabel(d!.period)}`}>
            {d!.entries.map((s) => <ModeratorRow key={`${s.community.id}-${s.moderator.id}`} s={s} formula={d!.formula} />)}
          </ol>
        )}
      </Card>
      {d?.computedAt && <p className="px-4 text-xs text-faint sm:px-0">Scores recomputed {timeAgo(d.computedAt)}. Select a row to see how its score is made up.</p>}
    </div>
  );
}

function TopicsTab() {
  const q = useReputationOverview();
  if (q.isPending) return <div className="grid gap-3 sm:grid-cols-2">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-36" />)}</div>;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  return (
    <div className="space-y-3">
      <div className="grid gap-3 px-4 sm:grid-cols-2 sm:px-0">
        {q.data.topics.map((t) => (
          <Card key={t.topic.id}>
            <CardHeader title={t.topic.name} action={<Link to={`/topics/${t.topic.slug}/leaders`} className="text-xs font-medium text-muted hover:text-text">See all</Link>} />
            {t.leaders.length === 0 ? (
              <p className="px-4 py-3 text-sm text-muted">No reputation earned here yet.</p>
            ) : (
              <ol className="divide-y divide-border">
                {t.leaders.map((e) => (
                  <li key={e.user.id} className="flex items-center gap-2.5 px-4 py-2">
                    <span className="w-5 text-xs font-bold tabular-nums text-muted">#{e.rank}</span>
                    <Avatar src={e.user.avatarUrl} name={e.user.username} size="sm" />
                    <Link to={`/u/${e.user.username}`} className="min-w-0 flex-1 truncate text-sm font-medium hover:underline">u/{e.user.username}</Link>
                    <span className="text-sm font-semibold tabular-nums">{formatPoints(e.points)}</span>
                  </li>
                ))}
              </ol>
            )}
            <p className="border-t border-border px-4 py-2 text-[11px] text-faint">{t.contributors} {t.contributors === 1 ? 'member has' : 'members have'} reputation here</p>
          </Card>
        ))}
      </div>
      {q.data.computedAt && <p className="px-4 text-xs text-faint sm:px-0">Totals rebuilt {timeAgo(q.data.computedAt)}; new votes are added within seconds.</p>}
    </div>
  );
}

function Rail({ tab }: { tab: Tab }) {
  const q = useModLeaderboard('', '');
  if (tab === 'topics') {
    return (
      <Card>
        <CardHeader title="Topic reputation" />
        <div className="space-y-2 p-4 text-sm text-muted">
          <p>Members earn reputation in a topic when others upvote their posts and comments there. A post’s topics are its community’s topic plus any tags that name a topic.</p>
          <p>Upvotes from people who already know the topic count more; brand-new and low-karma accounts count less; self-votes never count.</p>
          <p>Open a topic to see the exact caps and weights.</p>
        </div>
      </Card>
    );
  }
  return (
    <Card>
      <CardHeader title="How the score works" />
      <div className="p-4">{q.data ? <FormulaPanel formula={q.data.formula} /> : <Skeleton className="h-40" />}</div>
    </Card>
  );
}

/** Moderator recognition and topic reputation leaderboards. */
export default function LeaderboardPage() {
  const [params, setParams] = useSearchParams();
  const tab: Tab = params.get('tab') === 'topics' ? 'topics' : 'moderators';
  useDocumentTitle(tab === 'topics' ? 'Topic leaderboards' : 'Moderator leaderboard');
  return (
    <PageColumns rail={<Rail tab={tab} />}>
      <PageHeader
        title="Leaderboard"
        description={tab === 'topics' ? 'Who members trust in each topic, from quality-weighted votes.' : 'Moderators recognised for careful, timely, fair moderation, not for how much they removed.'}
      />
      <div className="mb-3 px-3 sm:px-0">
        <Segmented<Tab>
          label="Leaderboard type"
          value={tab}
          onChange={(v) => setParams(v === 'topics' ? { tab: v } : {}, { replace: true })}
          options={[
            { value: 'moderators', label: 'Moderators', icon: <Trophy size={14} /> },
            { value: 'topics', label: 'Topics', icon: <Award size={14} /> },
          ]}
        />
      </div>
      {tab === 'moderators' ? <ModeratorsTab /> : <TopicsTab />}
      <div className="mt-4 px-4 lg:hidden"><Rail tab={tab} /></div>
    </PageColumns>
  );
}

