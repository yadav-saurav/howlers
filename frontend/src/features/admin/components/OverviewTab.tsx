import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Skeleton } from '@/components/ui/Display';
import { ErrorState } from '@/components/ui/States';
import { compactNumber } from '@/utils/format';
import { useAdminOverview } from '../api';
import { signupSeries, sumCounts } from '../helpers';

function Tile({ label, value, hint, onClick }: { label: string; value: number; hint?: string; onClick?: () => void }) {
  const body = (
    <>
      <div className="text-xl font-bold tabular-nums">{compactNumber(value)}</div>
      <div className="text-xs text-muted">{label}</div>
      {hint && <div className="mt-0.5 text-[11px] text-faint">{hint}</div>}
    </>
  );
  return onClick ? (
    <button type="button" onClick={onClick} className="rounded-xl bg-surface-2/60 px-4 py-3 text-left hover:bg-surface-2">{body}</button>
  ) : (
    <div className="rounded-xl bg-surface-2/60 px-4 py-3">{body}</div>
  );
}

/** Site-wide counts and the last 14 days of sign-ups, all computed from real rows on the server. */
export function OverviewTab({ go }: { go: (tab: 'users' | 'communities' | 'flags') => void }) {
  const q = useAdminOverview();
  if (q.isPending) return <Skeleton className="h-72 rounded-2xl" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const o = q.data;
  const series = signupSeries(o.signups);
  const total = sumCounts(o.signups);
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Tile label="Accounts" value={o.users} hint={`${o.newUsers7d} new in 7 days`} onClick={() => go('users')} />
        <Tile label="Suspended" value={o.suspendedUsers} onClick={() => go('users')} />
        <Tile label="Communities" value={o.communities} hint={`${o.quarantinedCommunities} quarantined · ${o.bannedCommunities} banned`} onClick={() => go('communities')} />
        <Tile label="Posts" value={o.posts} />
        <Tile label="Comments" value={o.comments} />
        <Tile label="Open reports" value={o.openReports} onClick={() => go('flags')} />
        <Tile label="Held by spam checks" value={o.openFlags} onClick={() => go('flags')} />
      </div>
      <section className="rounded-2xl border border-border p-4" aria-labelledby="signups-title">
        <div className="mb-3 flex items-baseline justify-between gap-2">
          <h2 id="signups-title" className="text-sm font-semibold">Sign-ups, last 14 days (UTC)</h2>
          <span className="text-xs text-muted">{total} total</span>
        </div>
        <div className="h-48" role="img" aria-label={`Sign-ups per day for the last 14 days: ${series.map((p) => `${p.label} ${p.count}`).join(', ')}`}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={series} margin={{ left: -24, right: 4, top: 4, bottom: 0 }}>
              <XAxis dataKey="label" tickLine={false} axisLine={false} interval="preserveStartEnd" minTickGap={12}
                tick={{ fill: 'rgb(var(--text-faint))', fontSize: 11 }} />
              <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={40} tick={{ fill: 'rgb(var(--text-faint))', fontSize: 11 }} />
              <Tooltip
                cursor={{ fill: 'rgb(var(--surface-2))' }}
                contentStyle={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))', borderRadius: 8, fontSize: 12 }}
                labelStyle={{ color: 'rgb(var(--text))' }}
                itemStyle={{ color: 'rgb(var(--text-muted))' }}
                formatter={(v: number) => [v, 'Sign-ups']}
              />
              <Bar dataKey="count" fill="rgb(var(--brand))" radius={[4, 4, 0, 0]} maxBarSize={28} isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </section>
    </div>
  );
}
