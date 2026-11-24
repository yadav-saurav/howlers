import { Award, ChevronDown, ShieldCheck, Trophy, UsersRound } from 'lucide-react';
import { useId, useMemo, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Avatar, Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { Select } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Segmented } from '@/components/ui/Tabs';
import { useMyCommunities } from '@/features/communities/hooks';
import { useMemberLeaderboard, useModLeaderboard, useReputationOverview } from '@/features/reputation/api';
import { formatPoints, periodLabel } from '@/features/reputation/logic';
import { FormulaPanel, ModBadges, ScoreBreakdown } from '@/features/reputation/components/ScoreBreakdown';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import type { ModeratorScore, ScoreFormula } from '@/types/recognition';
import { timeAgo } from '@/utils/format';

type Tab = 'members' | 'moderators' | 'topics';

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
            description={`Moderators appear once they have at least ${d!.formula.minActivity === 1 ? 'one removal, ban or handled report' : `${d!.formula.minActivity} removals, bans or handled reports`} in the month. Scores are computed from real moderation activity only, so a quiet month stays empty.`}
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

const MEDALS = ['bg-[#f5c542] text-[#3b2a00]', 'bg-[#c9ced6] text-[#22262b]', 'bg-[#d98c4f] text-[#2c1404]'];

/** Most active members this month: fills in from the very first post. */
function MembersTab() {
  const [params, setParams] = useSearchParams();
  const period = params.get('period') ?? '';
  const community = params.get('community') ?? '';
  const q = useMemberLeaderboard(period, community);
  const mine = useMyCommunities();
  const set = (k: string, v: string) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v); else next.delete(k);
    setParams(next, { replace: true });
  };
  const d = q.data;
  const joined = mine.data ?? [];
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2 px-4 sm:px-0">
        <label className="sr-only" htmlFor="lbm-period">Period</label>
        <Select id="lbm-period" className="w-auto min-w-[170px]" value={d?.period ?? period} onChange={(e) => set('period', e.target.value)}>
          {(d?.periods ?? (period ? [period] : [])).map((p) => <option key={p} value={p}>{periodLabel(p)}</option>)}
        </Select>
        <label className="sr-only" htmlFor="lbm-scope">Community</label>
        <Select id="lbm-scope" className="w-auto min-w-[190px]" value={community} onChange={(e) => set('community', e.target.value)}>
          <option value="">All public communities</option>
          {joined.map((c) => <option key={c.slug} value={c.slug}>c/{c.slug}</option>)}
          {community && !joined.some((c) => c.slug === community) && <option value={community}>c/{community}</option>}
        </Select>
      </div>
      <Card className="rounded-none border-x-0 sm:rounded-lg sm:border-x">
        {q.isPending ? (
          <div className="space-y-3 p-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-12" />)}</div>
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        ) : d!.entries.length === 0 ? (
          <EmptyState
            icon={UsersRound}
            title={`Nobody on the board for ${periodLabel(d!.period)} yet`}
            description="Post, comment or earn an upvote this month and you appear here straight away."
            mascot="sleepy"
          />
        ) : (
          <ol className="divide-y divide-border" aria-label={`Most active members for ${periodLabel(d!.period)}`}>
            {d!.entries.map((e) => (
              <li key={e.user.id} className="flex items-center gap-3 px-4 py-3">
                <span
                  className={`inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold tabular-nums ${MEDALS[e.rank - 1] ?? 'text-muted'}`}
                  aria-label={`Rank ${e.rank}`}
                >
                  {e.rank}
                </span>
                <Avatar src={e.user.avatarUrl} name={e.user.username} size="md" />
                <div className="min-w-0 flex-1">
                  <Link to={`/u/${e.user.username}`} className="inline-flex min-h-6 items-center truncate text-sm font-semibold hover:underline">
                    u/{e.user.username}
                  </Link>
                  <p className="text-xs text-muted">
                    {e.posts} {e.posts === 1 ? 'post' : 'posts'} · {e.comments} {e.comments === 1 ? 'comment' : 'comments'} · {e.upvotes} {e.upvotes === 1 ? 'upvote' : 'upvotes'}
                  </p>
                </div>
                <span className="shrink-0 text-right">
                  <span className="block text-lg font-bold tabular-nums text-accent">{e.points}</span>
                  <span className="block text-[11px] text-muted">points</span>
                </span>
              </li>
            ))}
          </ol>
        )}
      </Card>
      <p className="px-4 text-xs text-faint sm:px-0">Updates as people post, comment and vote.</p>
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

/** A card whose explanation stays folded until it's tapped, so the ranking stays the focus. */
function Explainer({ title, children }: { title: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  return (
    <Card>
      <h2>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((o) => !o)}
          className="flex min-h-11 w-full items-center justify-between gap-2 px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted hover:text-text"
        >
          {title}
          <ChevronDown size={16} className={`shrink-0 transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
        </button>
      </h2>
      <div id={panelId} hidden={!open} className="border-t border-border">
        {open && children}
      </div>
    </Card>
  );
}

function Rail({ tab }: { tab: Tab }) {
  const q = useModLeaderboard('', '');
  if (tab === 'members') {
    return (
      <Explainer title="How points work">
        <ul className="space-y-1.5 p-4 text-sm text-muted">
          <li><strong className="text-text">3 points</strong> for each post</li>
          <li><strong className="text-text">1 point</strong> for each comment</li>
          <li><strong className="text-text">1 point</strong> for each upvote from someone else</li>
          <li className="pt-1 text-xs text-faint">Self-votes, deleted or removed posts and private communities never count. The board starts fresh every month.</li>
        </ul>
      </Explainer>
    );
  }
  if (tab === 'topics') {
    return (
      <Explainer title="Topic reputation">
        <div className="space-y-2 p-4 text-sm text-muted">
          <p>Members earn reputation in a topic when others upvote their posts and comments there. A post’s topics are its community’s topic plus any tags that name a topic.</p>
          <p>Upvotes from people who already know the topic count more; brand-new and low-karma accounts count less; self-votes never count.</p>
          <p>Open a topic to see the exact caps and weights.</p>
        </div>
      </Explainer>
    );
  }
  return (
    <Explainer title="How the score works">
      <div className="p-4">{q.data ? <FormulaPanel formula={q.data.formula} /> : <Skeleton className="h-40" />}</div>
    </Explainer>
  );
}

/** Moderator recognition and topic reputation leaderboards. */
export default function LeaderboardPage() {
  const [params, setParams] = useSearchParams();
  const raw = params.get('tab');
  const tab: Tab = raw === 'topics' || raw === 'moderators' ? raw : 'members';
  useDocumentTitle(tab === 'topics' ? 'Topic leaderboards' : tab === 'moderators' ? 'Moderator leaderboard' : 'Leaderboard');
  const description = {
    members: 'The most active members this month: posts, comments and the upvotes they earn.',
    moderators: 'Moderators recognised for careful, timely, fair moderation, not for how much they removed.',
    topics: 'Who members trust in each topic, from quality-weighted votes.',
  }[tab];
  return (
    <PageColumns rail={<Rail tab={tab} />}>
      <PageHeader title="Leaderboard" description={description} />
      <div className="mb-3 px-3 sm:px-0">
        <Segmented<Tab>
          label="Leaderboard type"
          value={tab}
          onChange={(v) => setParams(v === 'members' ? {} : { tab: v }, { replace: true })}
          options={[
            { value: 'members', label: 'Members', icon: <UsersRound size={14} /> },
            { value: 'moderators', label: 'Moderators', icon: <Trophy size={14} /> },
            { value: 'topics', label: 'Topics', icon: <Award size={14} /> },
          ]}
        />
      </div>
      {tab === 'members' ? <MembersTab /> : tab === 'moderators' ? <ModeratorsTab /> : <TopicsTab />}
      <div className="mt-4 px-4 lg:hidden"><Rail tab={tab} /></div>
    </PageColumns>
  );
}
