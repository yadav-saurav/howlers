import { useState } from 'react';
import { Bar, BarChart, Cell, LabelList, ResponsiveContainer, XAxis, YAxis } from 'recharts';
import { Button } from '@/components/ui/Button';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { errorMessage } from '@/services/apiError';
import type { Poll } from '@/types/api';
import { cn, pluralize, timeUntil } from '@/utils/format';
import { usePollVote } from '../hooks';

/** Poll with voting; once voted (or closed) results render as a horizontal bar chart. */
export function PollView({ postId, poll, compact }: { postId: number; poll: Poll; compact?: boolean }) {
  const { isAuthed } = useAuth();
  const vote = usePollVote(postId);
  const [picked, setPicked] = useState<number[]>(poll.myOptionIds);
  const [changing, setChanging] = useState(false);
  const voted = poll.myOptionIds.length > 0;
  const showResults = (voted && !changing) || poll.closed || !isAuthed;

  const toggle = (id: number) =>
    setPicked((p) => (poll.multiple ? (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]) : [id]));

  const submit = () =>
    vote.mutate(picked, {
      onSuccess: () => {
        setChanging(false);
        toast.success('Vote recorded');
      },
      onError: (e) => toast.error(errorMessage(e)),
    });

  const meta = (
    <p className="mt-2 text-xs text-faint">
      {pluralize(poll.totalVotes, 'vote')}
      {poll.closed ? ' · closed' : poll.closesAt ? ` · closes in ${timeUntil(poll.closesAt)}` : ''}
      {poll.multiple && ' · multiple choice'}
      {!isAuthed && !poll.closed && ' · log in to vote'}
    </p>
  );

  if (showResults) {
    const data = poll.options.map((o) => ({
      ...o,
      pct: poll.totalVotes ? Math.round((o.votes / poll.totalVotes) * 100) : 0,
      mine: poll.myOptionIds.includes(o.id),
    }));
    const height = data.length * (compact ? 30 : 36) + 8;
    return (
      // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- only stops a stray click from opening the post; not an interaction
      <div className="rounded-md border border-border bg-surface-2/50 p-3" onClick={(e) => e.stopPropagation()}>
        <div style={{ height }} aria-hidden="true">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} layout="vertical" margin={{ top: 0, right: 44, bottom: 0, left: 0 }} barCategoryGap={6}>
              <XAxis type="number" hide domain={[0, 100]} />
              <YAxis
                type="category"
                dataKey="label"
                width={compact ? 120 : 170}
                tickLine={false}
                axisLine={false}
                tick={{ fill: 'rgb(var(--text-muted))', fontSize: 13 }}
              />
              <Bar dataKey="pct" radius={[3, 3, 3, 3]} background={{ fill: 'rgb(var(--border) / 0.5)', radius: 3 }} isAnimationActive={false}>
                {data.map((d) => (
                  <Cell key={d.id} fill={d.mine ? 'rgb(var(--accent))' : 'rgb(var(--text-faint))'} />
                ))}
                <LabelList dataKey="pct" position="right" formatter={(v: number) => `${v}%`} style={{ fill: 'rgb(var(--text))', fontSize: 12 }} />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
        <ul className="sr-only">
          {data.map((d) => (
            <li key={d.id}>{`${d.label}: ${d.votes} votes (${d.pct}%)${d.mine ? ', your vote' : ''}`}</li>
          ))}
        </ul>
        <div className="flex items-center justify-between">
          {meta}
          {voted && !poll.closed && isAuthed && (
            <button className="mt-2 text-xs text-muted underline-offset-2 hover:text-text hover:underline" onClick={() => setChanging(true)}>
              Change vote
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions -- only stops a click on the poll from opening the post; the radios inside are the controls
    <fieldset className="rounded-md border border-border bg-surface-2/50 p-3" onClick={(e) => e.stopPropagation()}>
      <legend className="sr-only">Poll options</legend>
      <div className="space-y-1.5">
        {poll.options.map((o) => (
          <label
            key={o.id}
            className={cn(
              'flex cursor-pointer items-center gap-3 rounded-md border px-3 py-2 text-sm transition-colors',
              picked.includes(o.id) ? 'border-accent bg-accent/10' : 'border-border hover:bg-surface-2',
            )}
          >
            <input
              type={poll.multiple ? 'checkbox' : 'radio'}
              name={`poll-${postId}`}
              checked={picked.includes(o.id)}
              onChange={() => toggle(o.id)}
              className="accent-[rgb(var(--accent))]"
            />
            {o.label}
          </label>
        ))}
      </div>
      <div className="mt-3 flex items-center justify-between gap-3">
        {meta}
        <Button size="sm" onClick={submit} disabled={!picked.length} loading={vote.isPending}>
          Vote
        </Button>
      </div>
    </fieldset>
  );
}
