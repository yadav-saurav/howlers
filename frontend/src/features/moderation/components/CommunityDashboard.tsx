import { AlertTriangle, ShieldCheck } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { ErrorState } from '@/components/ui/States';
import { Switch } from '@/components/ui/Switch';
import { toast } from '@/components/ui/Toast';
import { automodApi, useAutomodMutation, useAutomodRules } from '@/features/automod/api';
import { useMemberLeaderboard } from '@/features/reputation/api';
import { errorMessage } from '@/services/apiError';
import { compactNumber } from '@/utils/format';
import type { AutomodRuleInput } from '@/types/automod';
import { useModOverview } from '../api';
import { ruleFor, SHIELDS, type Shield } from './shields';

type GoTab = 'queue' | 'held' | 'automod' | 'appeals' | 'bans' | 'log' | 'team';
const card = 'rounded-2xl border border-border bg-surface';

function Tile({ label, value, hint, onClick, alert }: { label: string; value: number; hint?: string; onClick?: () => void; alert?: boolean }) {
  const body = (
    <>
      <div className={`text-xl font-bold tabular-nums ${alert && value > 0 ? 'text-danger' : ''}`}>{compactNumber(value)}</div>
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

function ShieldRow({ slug, shield }: { slug: string; shield: Shield }) {
  const rules = useAutomodRules(slug);
  const existing = ruleFor(rules.data, shield);
  const on = !!existing?.enabled;
  const save = useAutomodMutation(slug, (enable: boolean) => {
    if (existing) return automodApi.update(slug, existing.id, { ...toInput(existing), enabled: enable });
    return automodApi.create(slug, { ...shield.rule, enabled: enable });
  });
  return (
    <li className="flex items-start gap-3 px-4 py-1">
      <span className={on ? 'mt-3.5 text-accent' : 'mt-3.5 text-faint'} aria-hidden="true"><ShieldCheck size={18} /></span>
      <div className="min-w-0 flex-1">
        <Switch
          checked={on}
          disabled={rules.isPending || save.isPending}
          label={shield.title}
          description={shield.description}
          onCheckedChange={(v: boolean) => save.mutate(v, {
            onSuccess: () => toast.success(`${shield.title} ${v ? 'is on' : 'turned off'}`),
            onError: (e) => toast.error(errorMessage(e)),
          })}
        />
        {existing && <p className="-mt-2 pb-3 text-[11px] text-faint">Caught {existing.hits30d} in the last 30 days · fine-tune it on the Automod tab</p>}
      </div>
    </li>
  );
}

const toInput = (r: AutomodRuleInput): AutomodRuleInput => ({
  name: r.name, kind: r.kind, pattern: r.pattern, threshold: r.threshold, appliesTo: r.appliesTo, action: r.action,
  reason: r.reason, enabled: r.enabled,
});

/**
 * Mod tools → Dashboard: everything a community owner overlooks at a glance. Live numbers, the last 14 days of activity,
 * one-tap safety shields (abusive language, link spam, brand-new accounts) and this month's most active members.
 */
export function CommunityDashboard({ slug, go }: { slug: string; go: (tab: GoTab) => void }) {
  const q = useModOverview(slug);
  const top = useMemberLeaderboard('', slug);
  if (q.isPending) return <Skeleton className="h-96 rounded-2xl" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const o = q.data;
  const attention = o.openReports + o.heldForReview + o.pendingAppeals;
  const series = o.days.map((d) => ({ ...d, label: d.day.slice(5).replace('-', '/') }));
  const leaders = (top.data?.entries ?? []).slice(0, 5);

  return (
    <div className="space-y-5">
      {attention > 0 ? (
        <div className="flex flex-wrap items-center gap-2 rounded-2xl border border-danger/40 bg-danger/10 px-4 py-3 text-sm">
          <AlertTriangle size={16} className="text-danger" aria-hidden="true" />
          <span className="font-semibold">Needs your attention:</span>
          {o.openReports > 0 && <button type="button" className="underline" onClick={() => go('queue')}>{o.openReports} reported</button>}
          {o.heldForReview > 0 && <button type="button" className="underline" onClick={() => go('held')}>{o.heldForReview} held</button>}
          {o.pendingAppeals > 0 && <button type="button" className="underline" onClick={() => go('appeals')}>{o.pendingAppeals} appeals</button>}
        </div>
      ) : (
        <div className="flex items-center gap-2 rounded-2xl border border-border bg-surface-2/40 px-4 py-3 text-sm text-muted">
          <ShieldCheck size={16} className="text-accent" aria-hidden="true" /> All clear: nothing reported, held or appealed right now.
        </div>
      )}

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Tile label="Members" value={o.members} hint={`${o.newMembers7d} joined in 7 days`} onClick={() => go('team')} />
        <Tile label="Posts (30 days)" value={o.posts30d} />
        <Tile label="Comments (30 days)" value={o.comments30d} />
        <Tile label="Removed (30 days)" value={o.removals30d} onClick={() => go('log')} />
        <Tile label="Open reports" value={o.openReports} onClick={() => go('queue')} alert />
        <Tile label="Held for review" value={o.heldForReview} onClick={() => go('held')} alert />
        <Tile label="Caught by automod (30 days)" value={o.automodHits30d} onClick={() => go('automod')} />
        <Tile label="Active bans" value={o.activeBans} onClick={() => go('bans')} />
      </div>

      <section className={`${card} p-4`} aria-labelledby="activity-title">
        <div className="mb-3 flex items-baseline justify-between gap-2">
          <h2 id="activity-title" className="text-sm font-semibold">Activity, last 14 days (UTC)</h2>
          <span className="text-xs text-muted">posts · comments · new members</span>
        </div>
        <div className="h-48" role="img"
          aria-label={`Posts, comments and new members per day: ${series.map((p) => `${p.label} ${p.posts}/${p.comments}/${p.joins}`).join(', ')}`}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={series} margin={{ left: -8, right: 4, top: 4, bottom: 0 }}>
              <XAxis dataKey="label" tickLine={false} axisLine={false} interval="preserveStartEnd" minTickGap={12}
                tick={{ fill: 'rgb(var(--text-faint))', fontSize: 11 }} />
              <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={40} tick={{ fill: 'rgb(var(--text-faint))', fontSize: 11 }} />
              <Tooltip
                cursor={{ fill: 'rgb(var(--surface-2))' }}
                contentStyle={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))', borderRadius: 8, fontSize: 12 }}
                labelStyle={{ color: 'rgb(var(--text))' }}
                itemStyle={{ color: 'rgb(var(--text-muted))' }}
              />
              <Bar dataKey="posts" name="Posts" stackId="a" fill="rgb(var(--brand))" maxBarSize={28} isAnimationActive={false} />
              <Bar dataKey="comments" name="Comments" stackId="a" fill="rgb(var(--accent))" maxBarSize={28} isAnimationActive={false} />
              <Bar dataKey="joins" name="New members" stackId="a" fill="rgb(var(--down))" radius={[4, 4, 0, 0]} maxBarSize={28} isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </section>

      <section className={card} aria-labelledby="shields-title">
        <div className="border-b border-border px-4 py-3">
          <h2 id="shields-title" className="text-sm font-semibold">Safety shields</h2>
          <p className="text-xs text-muted">Turn on in one tap. Moderators are never affected, and every change is logged.</p>
        </div>
        <ul className="divide-y divide-border">
          {SHIELDS.map((s) => <ShieldRow key={s.id} slug={slug} shield={s} />)}
        </ul>
      </section>

      <section className={card} aria-labelledby="top-title">
        <div className="flex items-baseline justify-between border-b border-border px-4 py-3">
          <h2 id="top-title" className="text-sm font-semibold">Most active members this month</h2>
          <Link to={`/leaderboard?community=${encodeURIComponent(slug)}`} className="text-xs text-accent hover:underline">Full board</Link>
        </div>
        {leaders.length === 0 ? (
          <p className="px-4 py-3 text-sm text-muted">Nobody has posted or commented here this month yet.</p>
        ) : (
          <ol className="divide-y divide-border">
            {leaders.map((e) => (
              <li key={e.user.id} className="flex items-center gap-3 px-4 py-2.5">
                <span className="w-6 text-center text-sm font-bold tabular-nums text-muted">{e.rank}</span>
                <Avatar src={e.user.avatarUrl} name={e.user.username} size="sm" />
                <Link to={`/u/${e.user.username}`} className="min-w-0 flex-1 truncate text-sm font-medium hover:underline">u/{e.user.username}</Link>
                <span className="text-xs text-muted">{e.posts} {e.posts === 1 ? 'post' : 'posts'} · {e.comments} {e.comments === 1 ? 'comment' : 'comments'}</span>
                <span className="w-10 text-right text-sm font-semibold tabular-nums text-accent">{e.points}</span>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}
