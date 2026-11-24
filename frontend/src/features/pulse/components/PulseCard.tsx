import { BarChart3, Check, ChevronDown, Clock, MoreHorizontal, Trophy, Users } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Bar, BarChart, Cell, LabelList, ResponsiveContainer, XAxis, YAxis } from 'recharts';
import { Button } from '@/components/ui/Button';
import { Avatar, Badge } from '@/components/ui/Display';
import { Textarea } from '@/components/ui/Field';
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/Menu';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { useTopic } from '@/features/realtime/RealtimeProvider';
import { errorMessage } from '@/services/apiError';
import type { Pulse } from '@/types/moderation';
import { cn, pluralize, timeAgo } from '@/utils/format';
import { pulseApi, pulseKeys, usePulseMutation, usePulseReasons } from '../api';
import { pulseResults, pulseStatusLabel, TYPE_LABELS } from '../helpers';

function Results({ p }: { p: Pulse }) {
  const rows = pulseResults(p)!;
  const data = rows.map((r) => ({ ...r, name: r.label.length > 28 ? `${r.label.slice(0, 27)}…` : r.label }));
  return (
    <div>
      <div style={{ height: Math.max(rows.length * 38, 80) }} role="img" aria-label={rows.map((r) => `${r.label}: ${r.pct}%`).join(', ')}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} layout="vertical" margin={{ left: 0, right: 44, top: 2, bottom: 2 }}>
            <XAxis type="number" domain={[0, 100]} hide />
            <YAxis type="category" dataKey="name" width={130} tickLine={false} axisLine={false} tick={{ fill: 'rgb(var(--text-muted))', fontSize: 12 }} />
            <Bar dataKey="pct" radius={[0, 6, 6, 0]} barSize={22} isAnimationActive={false}>
              {data.map((r) => (
                <Cell key={r.id} fill={r.correct ? 'rgb(var(--accent))' : r.mine ? 'rgb(var(--brand))' : 'rgb(var(--surface-3))'} />
              ))}
              <LabelList dataKey="pct" position="right" formatter={(v: number) => `${v}%`} style={{ fill: 'rgb(var(--text))', fontSize: 12, fontWeight: 600 }} />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      <ul className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted">
        {rows.filter((r) => r.mine || r.correct).map((r) => (
          <li key={r.id} className="inline-flex items-center gap-1">
            {r.correct ? <Trophy size={12} className="text-accent" /> : <Check size={12} className="text-brand" />}
            {r.correct ? 'Correct: ' : 'Your vote: '}{r.label}
          </li>
        ))}
      </ul>
    </div>
  );
}

function Reasons({ p }: { p: Pulse }) {
  const [open, setOpen] = useState(false);
  const q = usePulseReasons(p.id, open);
  const label = (id: number) => p.options.find((o) => o.id === id)?.label ?? '';
  return (
    <div className="mt-3">
      <button type="button" onClick={() => setOpen((o) => !o)} aria-expanded={open} className="inline-flex items-center gap-1 text-xs font-semibold text-brand hover:underline">
        <ChevronDown size={13} className={cn('transition-transform', open && 'rotate-180')} /> {open ? 'Hide reasoning' : 'Show reasoning'}
      </button>
      {open && (
        <ul className="mt-2 space-y-2">
          {q.isPending && <li className="text-xs text-faint">Loading…</li>}
          {q.data?.length === 0 && <li className="text-xs text-faint">No one has shared reasoning yet.</li>}
          {q.data?.map((r, i) => (
            <li key={i} className="rounded-xl bg-surface-2/60 px-3 py-2 text-sm">
              <div className="mb-0.5 flex items-center gap-1.5 text-xs text-muted">
                {r.author ? <><Avatar src={r.author.avatarUrl} name={r.author.username} size="xs" /> u/{r.author.username}</> : 'Anonymous'}
                <span>· chose “{label(r.optionId)}” · {timeAgo(r.createdAt)}</span>
              </div>
              {r.text}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** A community Pulse: vote, see live results, share reasoning, and (for mods) close or resolve it. */
export function PulseCard({ p, compact }: { p: Pulse; compact?: boolean }) {
  const { isAuthed } = useAuth();
  const navigate = useNavigate();
  const [selected, setSelected] = useState<number[]>([]);
  const [reasoning, setReasoning] = useState('');
  const [changing, setChanging] = useState(false);
  const vote = usePulseMutation(() => pulseApi.vote(p.id, selected, reasoning.trim() || undefined));
  const close = usePulseMutation(() => pulseApi.close(p.id));
  const resolve = usePulseMutation((optionId: number) => pulseApi.resolve(p.id, optionId));
  const remove = usePulseMutation(() => pulseApi.remove(p.id));
  const qc = useQueryClient();

  const open = p.status === 'OPEN' && new Date(p.closesAt).getTime() > Date.now();
  // Live updates: the frame carries no tallies (hidden results stay hidden), so just refetch.
  useTopic(open ? `/topic/pulse.${p.id}` : null, () => void qc.invalidateQueries({ queryKey: pulseKeys.all }));
  const voted = p.myOptionIds.length > 0;
  const showForm = open && p.viewer.canVote && (!voted || changing);
  const canSeeResults = p.resultsVisible && !showForm;
  const reasonsEnabled = p.type === 'PREDICTION' || p.type === 'DEBATE';

  const toggle = (id: number) =>
    setSelected((s) => (p.multiple ? (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]) : [id]));

  const submit = () =>
    vote.mutate(undefined, {
      onSuccess: () => {
        setChanging(false);
        setSelected([]);
        toast.success(voted ? 'Vote changed' : 'Vote recorded');
      },
      onError: (e) => toast.error(errorMessage(e)),
    });

  const mod = (fn: () => void) => fn;

  return (
    <article className={cn('rounded-2xl border border-border', compact ? 'p-3' : 'p-4')} aria-labelledby={`pulse-${p.id}`}>
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
        <Badge tone="info" className="rounded-full px-2"><BarChart3 size={11} /> {TYPE_LABELS[p.type]}</Badge>
        {!compact && <Link to={`/c/${p.community.slug}?view=pulse`} className="font-semibold text-text hover:underline">c/{p.community.slug}</Link>}
        <span className="inline-flex items-center gap-1"><Clock size={12} /> {pulseStatusLabel(p)}</span>
        <span className="inline-flex items-center gap-1"><Users size={12} /> {pluralize(p.totalVoters, 'vote')}</span>
        {p.viewer.canManage && (
          <div className="ml-auto">
            <Menu>
              <MenuTrigger aria-label="Pulse moderation" className="inline-flex h-7 w-7 items-center justify-center rounded-full hover:bg-surface-2"><MoreHorizontal size={15} /></MenuTrigger>
              <MenuContent>
                {open && <MenuItem onSelect={mod(() => close.mutate(undefined, { onSuccess: () => toast.success('Pulse closed'), onError: (e) => toast.error(errorMessage(e)) }))}>Close now</MenuItem>}
                {!open && p.type === 'PREDICTION' && p.status !== 'RESOLVED' && (
                  <>
                    <MenuLabel>Resolve: which outcome happened?</MenuLabel>
                    {p.options.map((o) => (
                      <MenuItem key={o.id} icon={<Trophy />} onSelect={() => resolve.mutate(o.id, { onSuccess: () => toast.success('Prediction resolved; voters notified'), onError: (e) => toast.error(errorMessage(e)) })}>
                        {o.label}
                      </MenuItem>
                    ))}
                  </>
                )}
                <MenuSeparator />
                <MenuItem danger onSelect={() => remove.mutate(undefined, { onSuccess: () => toast.success('Pulse deleted'), onError: (e) => toast.error(errorMessage(e)) })}>Delete</MenuItem>
              </MenuContent>
            </Menu>
          </div>
        )}
      </div>

      <h3 id={`pulse-${p.id}`} className={cn('mt-2 font-semibold leading-snug', compact ? 'text-sm' : 'text-base')}>{p.title}</h3>
      {p.description && !compact && <p className="mt-1 text-sm text-muted">{p.description}</p>}

      <div className="mt-3">
        {showForm ? (
          <fieldset>
            <legend className="sr-only">{p.multiple ? 'Choose one or more options' : 'Choose one option'}</legend>
            <div className="space-y-1.5" role={p.multiple ? 'group' : 'radiogroup'}>
              {p.options.map((o) => {
                const on = selected.includes(o.id);
                return (
                  <button
                    key={o.id}
                    type="button"
                    role={p.multiple ? 'checkbox' : 'radio'}
                    aria-checked={on}
                    onClick={() => toggle(o.id)}
                    className={cn('flex w-full items-center gap-2.5 rounded-xl border px-3 py-2 text-left text-sm transition-colors', on ? 'border-brand bg-brand/10' : 'border-border hover:bg-surface-2')}
                  >
                    <span className={cn('flex h-4 w-4 shrink-0 items-center justify-center border', p.multiple ? 'rounded' : 'rounded-full', on ? 'border-brand bg-brand text-brand-fg' : 'border-faint')}>
                      {on && <Check size={11} />}
                    </span>
                    {o.label}
                  </button>
                );
              })}
            </div>
            {reasonsEnabled && !compact && (
              <Textarea value={reasoning} onChange={(e) => setReasoning(e.target.value)} rows={2} maxLength={500} placeholder={p.type === 'PREDICTION' ? 'Why do you think so? (optional)' : 'Your argument (optional)'} className="mt-2" aria-label="Reasoning" />
            )}
            <div className="mt-2 flex items-center gap-2">
              <Button size="sm" disabled={!selected.length} loading={vote.isPending} onClick={submit}>{voted ? 'Change vote' : 'Vote'}</Button>
              {changing && <Button size="sm" variant="ghost" onClick={() => setChanging(false)}>Cancel</Button>}
              {p.anonymous && <span className="text-xs text-faint">Anonymous: no one sees who voted for what.</span>}
            </div>
          </fieldset>
        ) : canSeeResults ? (
          <Results p={p} />
        ) : open && !isAuthed ? (
          <Button size="sm" onClick={() => navigate(`/login?next=${encodeURIComponent(`/c/${p.community.slug}?view=pulse`)}`)}>Log in to vote</Button>
        ) : open && !p.viewer.canVote ? (
          <p className="text-sm text-muted">You can't vote in this community right now. Results appear when it closes.</p>
        ) : (
          <p className="text-sm text-muted">Results appear after you vote or when the Pulse closes.</p>
        )}
      </div>

      {p.status === 'RESOLVED' && p.accuracy != null && (
        <p className="mt-2 text-xs font-semibold text-accent">{Math.round(p.accuracy * 100)}% of forecasters got it right.</p>
      )}
      {open && voted && p.allowChange && !changing && p.viewer.canVote && (
        <button type="button" onClick={() => { setSelected(p.myOptionIds); setChanging(true); }} className="mt-2 text-xs font-semibold text-brand hover:underline">
          Change my vote
        </button>
      )}
      {reasonsEnabled && !compact && p.resultsVisible && <Reasons p={p} />}
    </article>
  );
}
