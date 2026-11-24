import { ArrowLeft, Award } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import { Avatar, Badge, Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useTopicLeaders } from '@/features/reputation/api';
import { formatPoints, TIER_LABELS } from '@/features/reputation/logic';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import { ApiError } from '@/services/apiError';
import type { ReputationPolicy } from '@/types/recognition';
import { timeAgo } from '@/utils/format';

function PolicyCard({ p }: { p: ReputationPolicy }) {
  return (
    <Card>
      <CardHeader title="How reputation is earned" />
      <ul className="space-y-2 p-4 text-sm text-muted">
        <li>An upvote on your post or comment adds {p.upvote}; a downvote subtracts {Math.abs(p.downvote)}.</li>
        <li>
          Upvotes from members with reputation in the same topic count up to {p.upvote * 2}× (log scale, full weight at {p.qualitySaturation} points).
        </li>
        <li>
          Votes from accounts younger than {p.newAccountDays} days count {p.newAccountWeight}×; from accounts under {p.minKarma} karma, {p.lowKarmaWeight}×.
        </li>
        <li>Self-votes never count. Votes on content removed by moderators stop counting, and come back if it’s restored.</li>
        <li>
          Caps: one voter counts for at most {p.pairDailyCap} votes on the same person per day, and nobody gains more than {p.topicDailyCap} points
          in a topic per day. Losses are never capped.
        </li>
        <li>Content in private communities doesn’t earn public reputation.</li>
      </ul>
      <div className="border-t border-border p-4">
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted">Tiers</h3>
        <ul className="space-y-1 text-sm">
          {p.tiers.map((t) => (
            <li key={t.tier} className="flex justify-between"><span>{t.label}</span><span className="tabular-nums text-muted">{t.minPoints}+</span></li>
          ))}
          <li className="pt-1 text-xs text-faint">“Top contributor” = top {p.topRank} in the topic with at least {p.topMinPoints} points.</li>
        </ul>
      </div>
    </Card>
  );
}

/** `/topics/:slug/leaders`: per-topic reputation leaderboard. */
export default function TopicLeadersPage() {
  const { slug = '' } = useParams();
  const q = useTopicLeaders(slug);
  useDocumentTitle(q.data ? `${q.data.topic.name} leaders` : 'Topic leaders');

  if (q.isError) {
    return (
      <PageColumns>
        {q.error instanceof ApiError && q.error.status === 404 ? (
          <EmptyState icon={Award} title="No such topic" description="Topics come from the shared taxonomy used for communities and onboarding." />
        ) : (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        )}
      </PageColumns>
    );
  }
  const d = q.data;
  return (
    <PageColumns rail={d ? <PolicyCard p={d.policy} /> : undefined}>
      <Link to="/leaderboard?tab=topics" className="mb-1 inline-flex items-center gap-1.5 px-4 pt-3 text-sm text-muted hover:text-text sm:px-0 sm:pt-0">
        <ArrowLeft size={15} /> All topics
      </Link>
      <PageHeader title={d ? `${d.topic.name} leaders` : 'Topic leaders'} description={d?.topic.description ?? undefined} />
      <Card className="rounded-none border-x-0 sm:rounded-lg sm:border-x">
        {q.isPending ? (
          <div className="space-y-3 p-4">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-10" />)}</div>
        ) : d!.entries.length === 0 ? (
          <EmptyState icon={Award} title="No reputation here yet" description="When members upvote posts and comments in this topic, the people who wrote them appear here." mascot="sit" />
        ) : (
          <ol className="divide-y divide-border" aria-label={`${d!.topic.name} leaderboard`}>
            {d!.entries.map((e) => (
              <li key={e.user.id} className="flex items-center gap-3 px-4 py-2.5">
                <span className="w-8 shrink-0 text-center text-sm font-bold tabular-nums text-muted">#{e.rank}</span>
                <Avatar src={e.user.avatarUrl} name={e.user.username} size="md" />
                <div className="min-w-0 flex-1">
                  <Link to={`/u/${e.user.username}`} className="block truncate text-sm font-semibold hover:underline">u/{e.user.username}</Link>
                  <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-faint">
                    {e.topContributor && <Badge tone="accent" className="rounded-full px-2">Top contributor</Badge>}
                    {e.tier !== 'NONE' && <Badge className="rounded-full px-2">{TIER_LABELS[e.tier]}</Badge>}
                    <span>{e.upvotes} up · {e.downvotes} down counted</span>
                  </div>
                </div>
                <span className="text-base font-bold tabular-nums">{formatPoints(e.points)}</span>
              </li>
            ))}
          </ol>
        )}
      </Card>
      {d && (
        <p className="mt-2 px-4 text-xs text-faint sm:px-0">
          {d.contributors} {d.contributors === 1 ? 'member has' : 'members have'} reputation in {d.topic.name}.
          {d.computedAt && ` Totals rebuilt ${timeAgo(d.computedAt)}; new votes are added within seconds.`}
        </p>
      )}
      {d && <div className="mt-4 px-4 lg:hidden"><PolicyCard p={d.policy} /></div>}
    </PageColumns>
  );
}
