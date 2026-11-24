import { Award } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { useUserReputation } from '../api';
import { formatPoints, standingLabel } from '../logic';

/** Profile card: topic badges ("Top contributor in Security", "Trusted in Books", ...) with rank and points. */
export function TopicStandings({ username, isSelf }: { username: string; isSelf: boolean }) {
  const q = useUserReputation(username);
  if (q.isPending) return <Card><CardHeader title="Topic reputation" /><div className="p-4"><Skeleton className="h-10" /></div></Card>;
  if (q.isError || q.data.hidden) return null;
  const topics = q.data.topics;
  if (!topics.length && !isSelf) return null;
  return (
    <Card>
      <CardHeader title="Topic reputation" action={<Link to="/leaderboard?tab=topics" className="text-xs font-medium text-muted hover:text-text">Leaderboards</Link>} />
      {topics.length === 0 ? (
        <p className="p-4 text-sm text-muted">
          No topic reputation yet. It grows when other members upvote your posts and comments in topic communities.
        </p>
      ) : (
        <ul className="divide-y divide-border">
          {topics.slice(0, 6).map((t) => (
            <li key={t.topic.id} className="flex items-center gap-3 px-4 py-2.5">
              <span className={t.topContributor || t.tier === 'EXPERT' || t.tier === 'AUTHORITY' ? 'text-accent' : 'text-muted'} aria-hidden="true">
                <Award size={16} />
              </span>
              <div className="min-w-0 flex-1">
                <Link to={`/topics/${t.topic.slug}/leaders`} className="block truncate text-sm font-medium hover:underline">
                  {standingLabel(t, t.topic.name)}
                </Link>
                <p className="text-xs text-faint">#{t.rank} in {t.topic.name} · {t.upvotes} upvotes counted</p>
              </div>
              <span className="text-sm font-semibold tabular-nums">{formatPoints(t.points)}</span>
            </li>
          ))}
        </ul>
      )}
      <p className="border-t border-border px-4 py-2 text-[11px] text-faint">Earned from votes, weighted by voter expertise, with anti-farming caps.</p>
    </Card>
  );
}
