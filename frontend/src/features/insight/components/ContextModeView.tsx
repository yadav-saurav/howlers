import * as DM from '@radix-ui/react-dropdown-menu';
import { Bot, Check, ExternalLink, Loader2, Network, Sparkles, Tag } from 'lucide-react';
import { useMemo, type ReactNode } from 'react';
import { Skeleton } from '@/components/ui/Display';
import { Menu, MenuContent, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/Menu';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { errorMessage } from '@/services/apiError';
import type { ArgItem, ContextView, MapNode, Stance } from '@/types/knowledge';
import { cn } from '@/utils/format';
import { useContextView, useLabelStance } from '../api';
import { stanceOrigin } from '../engine';
import { STANCES, stanceMeta } from '../stance';
import { SourceLink } from './SourceLink';

function StanceChip({ item }: { item: ArgItem }) {
  const m = stanceMeta[item.stance];
  const Icon = m.icon;
  const origin = stanceOrigin(item);
  return (
    <span className="inline-flex items-center gap-1.5">
      <span
        className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold text-text"
        style={{ backgroundColor: `${m.color}26` }}
        title={origin.title}
      >
        <Icon size={11} style={{ color: m.color }} aria-hidden="true" /> {m.label}
        {origin.source === 'RULES' && <Sparkles size={10} className="opacity-70" aria-label="suggested by rules" />}
      </span>
      {origin.marker && (
        <span
          className="inline-flex items-center gap-0.5 rounded-full border border-border px-1.5 py-px text-[10px] font-semibold text-muted"
          title={origin.title}
        >
          <Bot size={10} aria-hidden="true" /> {origin.marker}
        </span>
      )}
    </span>
  );
}

/** Lets a signed-in reader correct a label. One label per person; the author's own label counts double. */
function LabelMenu({ item, postId }: { item: ArgItem; postId: number }) {
  const { isAuthed } = useAuth();
  const label = useLabelStance(postId);
  if (!isAuthed) return null;
  const set = (stance: Stance | null) =>
    label.mutate({ commentId: item.commentId, stance }, { onSuccess: () => toast.success(stance ? `Labelled as ${stanceMeta[stance].label}` : 'Label removed'), onError: (e) => toast.error(errorMessage(e)) });
  return (
    <Menu>
      <MenuTrigger className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold text-muted hover:bg-surface-3 hover:text-text">
        <Tag size={12} /> {item.myLabel ? 'Your label' : 'Label'}
      </MenuTrigger>
      <MenuContent align="end">
        <MenuLabel>How does this relate to what it replies to?</MenuLabel>
        {STANCES.map((s) => {
          const Icon = stanceMeta[s].icon;
          return (
            <DM.Item key={s} onSelect={() => set(s)} className="flex cursor-pointer items-center gap-2.5 rounded-md px-2.5 py-2 text-sm outline-none data-[highlighted]:bg-surface-2">
              <Icon size={15} style={{ color: stanceMeta[s].color }} />
              <span className="flex-1">{stanceMeta[s].label}</span>
              {(item.labelCounts[s] ?? 0) > 0 && <span className="text-xs text-faint">{item.labelCounts[s]}</span>}
              {item.myLabel === s && <Check size={14} className="text-accent" />}
            </DM.Item>
          );
        })}
        {item.myLabel && (
          <>
            <MenuSeparator />
            <DM.Item onSelect={() => set(null)} className="cursor-pointer rounded-md px-2.5 py-2 text-sm text-muted outline-none data-[highlighted]:bg-surface-2">
              Remove my label
            </DM.Item>
          </>
        )}
      </MenuContent>
    </Menu>
  );
}

function ArgCard({ item, postId, onJump }: { item: ArgItem; postId: number; onJump: (id: number) => void }) {
  return (
    <li className="rounded-xl bg-surface-2/60 px-3 py-2.5">
      <div className="flex items-center gap-2">
        <StanceChip item={item} />
        <span className="text-xs text-faint">{item.score} pts</span>
        <div className="ml-auto"><LabelMenu item={item} postId={postId} /></div>
      </div>
      <p className="mt-1.5 text-sm leading-relaxed">{item.excerpt}</p>
      <div className="mt-1"><SourceLink commentId={item.commentId} author={item.author?.username} onJump={onJump} /></div>
    </li>
  );
}

function Column({ title, hint, items, postId, onJump, accent }: { title: string; hint?: string; items: ArgItem[]; postId: number; onJump: (id: number) => void; accent?: string }) {
  return (
    <section className="min-w-0">
      <h3 className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-muted">
        {accent && <span className="h-2 w-2 rounded-full" style={{ backgroundColor: accent }} />}
        {title} <span className="font-normal normal-case tracking-normal text-faint">({items.length})</span>
      </h3>
      {hint && <p className="mt-0.5 text-xs text-faint">{hint}</p>}
      {items.length ? (
        <ul className="mt-2 space-y-2">{items.map((i) => <ArgCard key={i.commentId} item={i} postId={postId} onJump={onJump} />)}</ul>
      ) : (
        <p className="mt-2 rounded-xl border border-dashed border-border px-3 py-3 text-sm text-faint">None yet.</p>
      )}
    </section>
  );
}

function StanceBar({ v }: { v: ContextView }) {
  const total = STANCES.reduce((a, s) => a + (v.stats.byStance[s] ?? 0), 0) || 1;
  return (
    <div>
      <div className="flex h-2.5 overflow-hidden rounded-full bg-surface-2" role="img" aria-label="Distribution of comment stances">
        {STANCES.map((s) => (
          <span key={s} style={{ width: `${((v.stats.byStance[s] ?? 0) / total) * 100}%`, backgroundColor: stanceMeta[s].color }} />
        ))}
      </div>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
        {STANCES.map((s) => (
          <span key={s} className="inline-flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full" style={{ backgroundColor: stanceMeta[s].color }} />
            {stanceMeta[s].label} {v.stats.byStance[s] ?? 0}
          </span>
        ))}
      </div>
    </div>
  );
}

/** Indented reply tree of the strongest comments, coloured by stance: how arguments connect. */
function DiscussionMap({ nodes, onJump }: { nodes: MapNode[]; onJump: (id: number) => void }) {
  const rows = useMemo(() => {
    const byParent = new Map<number | null, MapNode[]>();
    const ids = new Set(nodes.map((n) => n.id));
    for (const n of nodes) {
      const key = n.parentId != null && ids.has(n.parentId) ? n.parentId : null;
      byParent.set(key, [...(byParent.get(key) ?? []), n]);
    }
    const out: { node: MapNode; depth: number }[] = [];
    const walk = (parent: number | null, depth: number) => {
      for (const n of byParent.get(parent) ?? []) {
        out.push({ node: n, depth });
        walk(n.id, depth + 1);
      }
    };
    walk(null, 0);
    return out;
  }, [nodes]);
  return (
    <ul className="space-y-0.5">
      {rows.map(({ node, depth }) => (
        <li key={node.id} style={{ paddingLeft: Math.min(depth, 6) * 18 }}>
          <button type="button" onClick={() => onJump(node.id)} className="group flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left hover:bg-surface-2">
            {depth > 0 && <span className="h-px w-3 bg-border" aria-hidden="true" />}
            <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: stanceMeta[node.stance].color, opacity: node.suggested ? 0.55 : 1 }} title={stanceMeta[node.stance].label} />
            <span className="min-w-0 flex-1 truncate text-sm text-text/85 group-hover:text-text">{node.label}</span>
            <span className="shrink-0 text-xs text-faint">{node.score}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function Block({ title, icon, children }: { title: string; icon?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-2xl border border-border p-4">
      <h3 className="mb-3 flex items-center gap-2 text-sm font-bold">{icon}{title}</h3>
      {children}
    </section>
  );
}

/**
 * Context Mode: an organised view of the arguments in a thread. It complements, never replaces, the thread.
 * Both sides always get equal space regardless of how many comments each has.
 */
export function ContextModeView({ postId, commentCount, onJump }: { postId: number; commentCount: number; onJump: (id: number) => void }) {
  const q = useContextView(postId, commentCount > 0);
  if (commentCount === 0) {
    return <EmptyState icon={Network} title="No arguments to map yet" description="Context Mode organises a discussion once people start replying." />;
  }
  if (q.isPending) return <div className="space-y-3"><Skeleton className="h-24" /><Skeleton className="h-40" /><Skeleton className="h-40" /></div>;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const v = q.data;
  const g = v.groups;

  return (
    <div className="space-y-4">
      <section className="rounded-2xl border border-brand/30 bg-brand/[0.05] p-4">
        <div className="text-xs font-bold uppercase tracking-wider text-brand">Main claim</div>
        <p className="mt-1 text-lg font-semibold leading-snug">{v.title}</p>
        {v.excerpt && <p className="mt-1 text-sm text-muted">{v.excerpt}</p>}
        <div className="mt-4"><StanceBar v={v} /></div>
        <p className="mt-3 text-xs text-faint">
          {v.stats.analysed} of {v.stats.total} comments analysed · {v.stats.participants} participants · {v.stats.labelled} labelled by readers,{' '}
          {(v.stats.aiSuggested ?? 0) > 0 && <>{v.stats.aiSuggested} AI-suggested, </>}
          {v.stats.suggested - (v.stats.aiSuggested ?? 0)} suggested by rules <Sparkles size={10} className="inline" />. Stances describe how a reply relates to what it answers, not whether it's true.
          Suggestions are never final: anyone signed in can correct them with Label, and reader labels always win.
          {v.truncated && ' Large thread: showing the highest-ranked comments.'}
        </p>
        {v.aiStances && v.aiStances.state !== 'OFF' && (
          <p className="mt-1.5 flex items-center gap-1.5 text-xs text-faint" role="status">
            {v.aiStances.state === 'PENDING' ? <Loader2 size={12} className="animate-spin" aria-hidden="true" /> : <Bot size={12} aria-hidden="true" />}
            {v.aiStances.note}
          </p>
        )}
      </section>

      <div className="grid gap-4 md:grid-cols-2">
        <Column title="Supporting arguments" items={g.SUPPORT} postId={postId} onJump={onJump} accent={stanceMeta.SUPPORT.color} />
        <Column title="Counterarguments" items={g.OPPOSE} postId={postId} onJump={onJump} accent={stanceMeta.OPPOSE.color} />
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <Block title="Where people agree" icon={<span className="h-2 w-2 rounded-full bg-accent" />}>
          <p className="mb-2 text-xs text-faint">Well-received points nobody pushed back on.</p>
          {v.agreement.length ? <ul className="space-y-2">{v.agreement.map((i) => <ArgCard key={i.commentId} item={i} postId={postId} onJump={onJump} />)}</ul> : <p className="text-sm text-faint">No clear common ground yet.</p>}
        </Block>
        <Block title="Where the disagreement is" icon={<span className="h-2 w-2 rounded-full bg-danger" />}>
          <p className="mb-2 text-xs text-faint">Points that were challenged or split the votes.</p>
          {v.disagreement.length ? <ul className="space-y-2">{v.disagreement.map((i) => <ArgCard key={i.commentId} item={i} postId={postId} onJump={onJump} />)}</ul> : <p className="text-sm text-faint">No contested points yet.</p>}
        </Block>
      </div>

      <Block title="Evidence cited by participants" icon={<ExternalLink size={15} className="text-info" />}>
        {v.evidence.length ? (
          <ul className="space-y-2">
            {v.evidence.map((e) => (
              <li key={e.url} className="rounded-xl bg-surface-2/60 px-3 py-2">
                <a href={e.url} target="_blank" rel="noopener noreferrer nofollow ugc" className="text-sm font-semibold text-brand hover:underline">{e.domain}</a>
                <p className="mt-0.5 line-clamp-2 text-xs text-muted">{e.excerpt}</p>
                <SourceLink commentId={e.commentId} onJump={onJump} />
              </li>
            ))}
          </ul>
        ) : <p className="text-sm text-faint">No links or sources cited yet. Nothing here is invented.</p>}
        {g.EVIDENCE.length > 0 && <ul className="mt-3 space-y-2">{g.EVIDENCE.map((i) => <ArgCard key={i.commentId} item={i} postId={postId} onJump={onJump} />)}</ul>}
      </Block>

      <div className="grid gap-4 md:grid-cols-2">
        <Column title="Unanswered questions" items={v.openQuestions} postId={postId} onJump={onJump} accent={stanceMeta.QUESTION.color} />
        <Column title="Personal experiences" items={g.EXPERIENCE} postId={postId} onJump={onJump} accent={stanceMeta.EXPERIENCE.color} />
      </div>

      <Block title="Discussion map" icon={<Network size={15} className="text-brand" />}>
        <p className="mb-2 text-xs text-faint">How replies connect. Faded dots are suggested stances (rules or AI), not reader labels; click any row to open the comment.</p>
        <DiscussionMap nodes={v.map} onJump={onJump} />
      </Block>

      {g.NEUTRAL.length > 0 && (
        <details className="rounded-2xl border border-border p-4">
          <summary className={cn('cursor-pointer text-sm font-bold')}>Other comments ({v.stats.byStance.NEUTRAL})</summary>
          <ul className="mt-3 space-y-2">{g.NEUTRAL.map((i) => <ArgCard key={i.commentId} item={i} postId={postId} onJump={onJump} />)}</ul>
        </details>
      )}
    </div>
  );
}
