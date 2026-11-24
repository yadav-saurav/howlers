import { AlertTriangle, ExternalLink, Info, RefreshCw, ScrollText, Sparkles } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { SortMenu } from '@/features/posts/components/FeedControls';
import { ApiError, errorMessage } from '@/services/apiError';
import type { ArgItem, DigestFocus, DigestLength, DigestLine } from '@/types/knowledge';
import { cn, timeAgo } from '@/utils/format';
import { useDigest, useRefreshDigest } from '../api';
import { coverageNote, digestEngineLabel, lineSources } from '../engine';
import { kindMeta } from '../stance';
import { SourceLink } from './SourceLink';

const LENGTHS: { value: DigestLength; label: string }[] = [
  { value: 'short', label: 'Very short' },
  { value: 'standard', label: 'Standard' },
  { value: 'detailed', label: 'Detailed' },
];
const FOCUSES: { value: DigestFocus; label: string }[] = [
  { value: 'balanced', label: 'Balanced viewpoints' },
  { value: 'top', label: 'Top-voted comments' },
  { value: 'new', label: 'Newest developments' },
  { value: 'beginner', label: 'Beginner-friendly' },
  { value: 'technical', label: 'Technical' },
];

function Section({ title, children, empty }: { title: string; children: ReactNode; empty?: boolean }) {
  if (empty) return null;
  return (
    <section className="space-y-2">
      <h3 className="text-xs font-bold uppercase tracking-wider text-muted">{title}</h3>
      {children}
    </section>
  );
}

function Lines({ lines, onJump }: { lines: DigestLine[]; onJump: (id: number) => void }) {
  return (
    <ul className="space-y-2.5">
      {lines.map((l, i) => (
        <li key={`${l.commentId}-${i}`} className={cn('border-l-2 pl-3', l.paraphrase ? 'border-border' : 'border-brand/40')}>
          {/* Verbatim lines get quotation marks; paraphrased (AI) points don't, so wording is never misattributed. */}
          <p className="text-[15px] leading-relaxed">{l.paraphrase ? l.text : `“${l.text}”`}</p>
          <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className={cn('text-[11px] font-semibold uppercase tracking-wide', (kindMeta[l.kind] ?? kindMeta.STATEMENT).tone)}>
              {(kindMeta[l.kind] ?? kindMeta.STATEMENT).label}
            </span>
            {lineSources(l).map((id, j) => (
              <SourceLink key={id} commentId={id} author={j === 0 ? l.author : null} onJump={onJump} />
            ))}
          </div>
        </li>
      ))}
    </ul>
  );
}

function Items({ items, onJump }: { items: ArgItem[]; onJump: (id: number) => void }) {
  return (
    <ul className="space-y-2">
      {items.map((a) => (
        <li key={a.commentId} className="rounded-xl bg-surface-2/60 px-3 py-2.5">
          <p className="text-sm leading-relaxed">{a.excerpt}</p>
          <div className="mt-1 flex items-center gap-3 text-xs text-faint">
            <span>{a.score} points</span>
            <SourceLink commentId={a.commentId} author={a.author?.username} onJump={onJump} />
          </div>
        </li>
      ))}
    </ul>
  );
}

/**
 * Thread Digest. Visually distinct (bordered "brief" panel) so it is never mistaken for a comment.
 * Shows how and when it was generated, flags staleness, and links every line back to its source.
 */
export function DigestPanel({ postId, commentCount, onJump }: { postId: number; commentCount: number; onJump: (id: number) => void }) {
  const [length, setLength] = useState<DigestLength>('standard');
  const [focus, setFocus] = useState<DigestFocus>('balanced');
  const q = useDigest(postId, length, focus, commentCount > 0);
  const refresh = useRefreshDigest(postId, length, focus);

  if (commentCount === 0) {
    return <EmptyState icon={ScrollText} title="Nothing to digest yet" description="A digest appears once people start discussing this post." />;
  }

  const d = q.data;
  const engine = digestEngineLabel(d?.engine ?? '');
  const coverage = d ? coverageNote(d) : null;
  const consensusTone = d?.sections.consensus.status === 'CONSENSUS' ? 'border-accent/40 bg-accent/10' : d?.sections.consensus.status === 'DIVIDED' ? 'border-warning/40 bg-warning/10' : 'border-border bg-surface-2/60';

  return (
    <div className="rounded-2xl border border-brand/30 bg-gradient-to-b from-brand/[0.06] to-transparent p-4 sm:p-5">
      <div className="flex flex-wrap items-center gap-2">
        <ScrollText size={18} className="text-brand" />
        <h2 className="mr-auto text-base font-bold">Thread digest</h2>
        <SortMenu title="Length" value={length} options={LENGTHS} onChange={setLength} />
        <SortMenu title="Focus" value={focus} options={FOCUSES} onChange={setFocus} />
      </div>

      {q.isPending && (
        <div className="mt-4 space-y-3">
          <Skeleton className="h-4 w-11/12" /><Skeleton className="h-4 w-4/5" /><Skeleton className="h-4 w-3/5" />
        </div>
      )}
      {q.isError && (
        <div className="mt-4">
          {q.error instanceof ApiError && q.error.status === 400 ? (
            <p className="text-sm text-muted">{q.error.message}</p>
          ) : (
            <ErrorState error={q.error} onRetry={() => q.refetch()} />
          )}
        </div>
      )}

      {d && (
        <div className="mt-4 space-y-6">
          <div className="space-y-1.5">
            <span
              className={cn(
                'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold',
                engine.ai ? 'bg-brand/15 text-brand' : 'bg-surface-2 text-muted',
              )}
            >
              {engine.ai ? <Sparkles size={11} aria-hidden="true" /> : <ScrollText size={11} aria-hidden="true" />}
              {engine.label}
            </span>
            <p className="text-xs text-muted">
              Generated {timeAgo(d.generatedAt)} from {d.commentCountAtGeneration} comments · {d.engineNote}
              {coverage && <> · {coverage}</>}
            </p>
          </div>
          {d.notice && (
            <div role="status" className="flex items-start gap-2 rounded-xl border border-info/40 bg-info/10 px-3 py-2 text-sm">
              <Info size={16} className="mt-0.5 shrink-0 text-info" />
              <span>{d.notice}</span>
            </div>
          )}
          {d.stale && (
            <div className="flex flex-wrap items-center gap-3 rounded-xl border border-warning/40 bg-warning/10 px-3 py-2 text-sm">
              <AlertTriangle size={16} className="text-warning" />
              <span className="flex-1">The discussion has grown to {d.currentCommentCount} comments since this digest was made.</span>
            </div>
          )}

          <Section title="Original question">
            <p className="text-[15px] leading-relaxed text-text/90">{d.sections.question}</p>
          </Section>

          <div className={cn('rounded-xl border px-3 py-2.5 text-sm font-medium', consensusTone)}>{d.sections.consensus.statement}</div>

          <Section title="Summary" empty={!d.sections.summary.length}>
            <Lines lines={d.sections.summary} onJump={onJump} />
          </Section>
          <Section title={engine.ai ? 'In their own words' : 'Key takeaways'} empty={!d.sections.takeaways.length}>
            <Lines lines={d.sections.takeaways} onJump={onJump} />
          </Section>
          <div className="grid gap-6 md:grid-cols-2">
            <Section title="Top supporting arguments" empty={!d.sections.supporting.length}>
              <Lines lines={d.sections.supporting} onJump={onJump} />
            </Section>
            <Section title="Top opposing arguments" empty={!d.sections.opposing.length}>
              <Lines lines={d.sections.opposing} onJump={onJump} />
            </Section>
          </div>
          <Section title="Most useful comments" empty={!d.sections.mostUseful.length}>
            <Items items={d.sections.mostUseful} onJump={onJump} />
          </Section>
          <Section title="Minority perspectives" empty={!d.sections.minority.length}>
            <p className="text-xs text-muted">Shown because they're the less common view, not because they're right or wrong.</p>
            <Items items={d.sections.minority} onJump={onJump} />
          </Section>
          <Section title="Unresolved questions" empty={!d.sections.unresolved.length && !d.sections.openQuestions?.length}>
            {!!d.sections.openQuestions?.length && <Lines lines={d.sections.openQuestions} onJump={onJump} />}
            <Items items={d.sections.unresolved} onJump={onJump} />
          </Section>
          <Section title="Links & resources" empty={!d.sections.links.length}>
            <ul className="space-y-1.5">
              {d.sections.links.map((l) => (
                <li key={l.url} className="flex items-center gap-2 text-sm">
                  <ExternalLink size={14} className="shrink-0 text-muted" />
                  <a href={l.url} target="_blank" rel="noopener noreferrer nofollow ugc" className="truncate text-brand hover:underline">{l.domain}</a>
                  <SourceLink commentId={l.commentId} onJump={onJump} />
                </li>
              ))}
            </ul>
          </Section>

          <div className="flex justify-end">
            <Button
              variant="secondary"
              size="sm"
              loading={refresh.isPending}
              onClick={() => refresh.mutate(undefined, { onSuccess: () => toast.success('Digest refreshed'), onError: (e) => toast.error(errorMessage(e)) })}
            >
              <RefreshCw size={14} /> Refresh digest
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
