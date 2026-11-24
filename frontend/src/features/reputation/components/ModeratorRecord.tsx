import { ShieldCheck } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { useModRecord } from '../api';
import { periodLabel } from '../logic';
import { ModBadges } from './ScoreBreakdown';

/** Profile card: the moderator's recent monthly scores and badges, per community. Hidden if they opted out. */
export function ModeratorRecord({ username, isSelf }: { username: string; isSelf: boolean }) {
  const q = useModRecord(username);
  if (q.isPending) return <Card><CardHeader title="Moderation" /><div className="p-4"><Skeleton className="h-10" /></div></Card>;
  if (q.isError || q.data.hidden || q.data.entries.length === 0) return null;
  const r = q.data;
  return (
    <Card>
      <CardHeader title="Moderation" action={<Link to="/leaderboard" className="text-xs font-medium text-muted hover:text-text">Leaderboard</Link>} />
      {isSelf && r.optedOut && (
        <p className="border-b border-border bg-surface-2/60 px-4 py-2 text-xs text-muted">
          You opted out of public ranking, so only you can see this. Change it in <Link to="/settings?tab=preferences" className="underline">Settings</Link>.
        </p>
      )}
      <ul className="divide-y divide-border">
        {r.entries.slice(0, 6).map((e) => (
          <li key={`${e.community.id}-${e.period}`} className="space-y-1.5 px-4 py-2.5">
            <div className="flex items-center gap-2">
              <ShieldCheck size={15} className="shrink-0 text-muted" aria-hidden="true" />
              <Link to={`/leaderboard?community=${e.community.slug}&period=${e.period}`} className="min-w-0 flex-1 truncate text-sm font-medium hover:underline">
                c/{e.community.slug}
              </Link>
              <span className="text-sm font-semibold tabular-nums">{e.score.toFixed(1)}</span>
            </div>
            <p className="text-xs text-faint">
              {periodLabel(e.period)} · {e.ranked && e.rank != null ? `#${e.rank} in community` : 'not ranked'}
            </p>
            <ModBadges badges={e.badges} />
          </li>
        ))}
      </ul>
      <p className="border-t border-border px-4 py-2 text-[11px] text-faint">Quality-weighted, never based on how much was removed.</p>
    </Card>
  );
}
