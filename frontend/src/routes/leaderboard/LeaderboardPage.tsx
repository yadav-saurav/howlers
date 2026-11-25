import { Building2, ChevronDown, UsersRound } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Avatar, Card, Skeleton } from '@/components/ui/Display';
import { Select } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Segmented } from '@/components/ui/Tabs';
import { useMyCommunities } from '@/features/communities/hooks';
import { useCommunityLeaderboard, useMemberLeaderboard } from '@/features/reputation/api';
import { periodLabel } from '@/features/reputation/logic';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';

type Tab = 'members' | 'communities';

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

/** A card whose explanation stays folded until it's tapped, so the ranking stays the focus. */
/** Most active communities this month: the points their members earn there. */
function CommunitiesTab() {
  const [params, setParams] = useSearchParams();
  const period = params.get('period') ?? '';
  const q = useCommunityLeaderboard(period);
  const set = (v: string) => {
    const next = new URLSearchParams(params);
    if (v) next.set('period', v); else next.delete('period');
    setParams(next, { replace: true });
  };
  const d = q.data;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2 px-4 sm:px-0">
        <label className="sr-only" htmlFor="lbc-period">Period</label>
        <Select id="lbc-period" className="w-auto min-w-[170px]" value={d?.period ?? period} onChange={(e) => set(e.target.value)}>
          {(d?.periods ?? (period ? [period] : [])).map((p) => <option key={p} value={p}>{periodLabel(p)}</option>)}
        </Select>
      </div>
      <Card className="rounded-none border-x-0 sm:rounded-lg sm:border-x">
        {q.isPending ? (
          <div className="space-y-3 p-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-12" />)}</div>
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        ) : d!.entries.length === 0 ? (
          <EmptyState
            icon={Building2}
            title={`No active communities for ${periodLabel(d!.period)} yet`}
            description="As soon as someone posts or comments in a public community, it appears here."
            mascot="sleepy"
          />
        ) : (
          <ol className="divide-y divide-border" aria-label={`Most active communities for ${periodLabel(d!.period)}`}>
            {d!.entries.map((e) => (
              <li key={e.community.id} className="flex items-center gap-3 px-4 py-3">
                <span
                  className={`inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold tabular-nums ${MEDALS[e.rank - 1] ?? 'text-muted'}`}
                  aria-label={`Rank ${e.rank}`}
                >
                  {e.rank}
                </span>
                <Avatar src={e.community.iconUrl} name={e.community.slug} size="md" />
                <div className="min-w-0 flex-1">
                  <Link to={`/c/${e.community.slug}`} className="inline-flex min-h-6 max-w-full items-center truncate text-sm font-semibold hover:underline">
                    c/{e.community.slug}
                  </Link>
                  <p className="text-xs text-muted">
                    {e.activeMembers} active {e.activeMembers === 1 ? 'member' : 'members'} · {e.posts} {e.posts === 1 ? 'post' : 'posts'} · {e.comments} {e.comments === 1 ? 'comment' : 'comments'} · {e.upvotes} {e.upvotes === 1 ? 'upvote' : 'upvotes'}
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
  if (tab === 'communities') {
    return (
      <Explainer title="How community points work">
        <div className="space-y-2 p-4 text-sm text-muted">
          <p>A community earns the points its members earn inside it this month:</p>
          <ul className="space-y-1.5">
            <li><strong className="text-text">2 points</strong> for each post</li>
            <li><strong className="text-text">1 point</strong> for each comment</li>
            <li><strong className="text-text">1 point</strong> for each upvote from someone else</li>
          </ul>
          <p className="pt-1 text-xs text-faint">Public communities only. Self-votes, deleted or removed posts never count. The board starts fresh every month.</p>
        </div>
      </Explainer>
    );
  }
  return (
    <Explainer title="How points work">
      <ul className="space-y-1.5 p-4 text-sm text-muted">
        <li><strong className="text-text">2 points</strong> for each post</li>
        <li><strong className="text-text">1 point</strong> for each comment</li>
        <li><strong className="text-text">1 point</strong> for each upvote from someone else</li>
        <li className="pt-1 text-xs text-faint">Self-votes, deleted or removed posts and private communities never count. The board starts fresh every month.</li>
      </ul>
    </Explainer>
  );
}

/** The Members and Communities leaderboards. */
export default function LeaderboardPage() {
  const [params, setParams] = useSearchParams();
  const raw = params.get('tab');
  const tab: Tab = raw === 'communities' ? 'communities' : 'members';
  useDocumentTitle(tab === 'communities' ? 'Community leaderboard' : 'Leaderboard');
  const description = {
    members: 'The most active members this month: posts, comments and the upvotes they earn.',
    communities: 'The liveliest communities this month: everything their members post, comment and earn there.',
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
            { value: 'communities', label: 'Communities', icon: <Building2 size={14} /> },
          ]}
        />
      </div>
      {tab === 'members' ? <MembersTab /> : <CommunitiesTab />}
      <div className="mt-4 px-4 lg:hidden"><Rail tab={tab} /></div>
    </PageColumns>
  );
}
