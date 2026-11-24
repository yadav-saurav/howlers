import { Copy, ExternalLink, Info, MessageSquare, Sparkles, ThumbsDown, ThumbsUp } from 'lucide-react';
import { useState } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import { Link } from 'react-router-dom';
import remarkGfm from 'remark-gfm';
import { Avatar } from '@/components/ui/Display';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { cn, timeAgo } from '@/utils/format';
import { useAiFeedback, type AiMessage, type AiSource } from '../api';
import { linkCitations, parseCitationHref } from '../citations';

function SourceCard({ s, messageId, active }: { s: AiSource; messageId: number; active: boolean }) {
  const body = (
    <>
      <div className="flex items-center gap-1.5 text-xs text-muted">
        <span className={cn('flex h-5 min-w-5 items-center justify-center rounded-full px-1 text-[11px] font-bold', s.cited ? 'bg-brand text-brand-fg' : 'bg-surface-3 text-muted')}>{s.n}</span>
        <Avatar name={s.communitySlug} size="xs" />
        <span className="font-semibold text-text">c/{s.communitySlug}</span>
        <span>·</span>
        <span className="inline-flex items-center gap-1">{s.kind === 'COMMENT' ? <><MessageSquare size={11} /> comment</> : 'post'}</span>
      </div>
      <div className="mt-1 line-clamp-1 text-sm font-semibold">{s.title}</div>
      <p className="mt-0.5 line-clamp-3 text-xs leading-relaxed text-muted">{s.excerpt}</p>
      <div className="mt-1 flex items-center gap-2 text-[11px] text-faint">
        {s.author && <span>u/{s.author}</span>}
        <span>{s.score} pts</span>
        <span>{timeAgo(s.createdAt)}</span>
        {s.link && <ExternalLink size={11} className="ml-auto" />}
      </div>
    </>
  );
  const cls = cn('block rounded-xl border bg-surface-2/50 p-3 transition-colors', active ? 'border-brand ring-2 ring-brand/30' : 'border-border hover:border-surface-3');
  return s.link ? (
    <Link id={`src-${messageId}-${s.n}`} to={s.link} className={cls}>{body}</Link>
  ) : (
    <div id={`src-${messageId}-${s.n}`} className={cn(cls, 'opacity-70')}>{body}</div>
  );
}

/** One assistant answer: cited markdown, source cards, follow-ups, copy and feedback. */
export function AnswerView({ m, conversationId, onFollowUp }: { m: AiMessage; conversationId: number; onFollowUp: (q: string) => void }) {
  const [active, setActive] = useState<number | null>(null);
  const [showAll, setShowAll] = useState(false);
  const fb = useAiFeedback(conversationId);
  const cited = m.sources.filter((s) => s.cited);
  const visible = showAll || cited.length === 0 ? m.sources : cited;

  const components: Components = {
    a: ({ href, children }) => {
      const c = parseCitationHref(href);
      if (c) {
        return (
          <button
            type="button"
            onClick={() => {
              setActive(c.n);
              if (!cited.some((s) => s.n === c.n)) setShowAll(true);
              window.setTimeout(() => document.getElementById(`src-${c.messageId}-${c.n}`)?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }), 30);
            }}
            className="mx-0.5 inline-flex h-[18px] min-w-[18px] -translate-y-0.5 items-center justify-center rounded-full bg-brand/20 px-1 align-middle text-[11px] font-bold text-brand hover:bg-brand hover:text-brand-fg"
            aria-label={`Source ${c.n}`}
          >
            {children}
          </button>
        );
      }
      if (href?.startsWith('/')) return <Link to={href}>{children}</Link>;
      const safe = href && /^https?:/i.test(href) ? href : undefined;
      return <a href={safe} target="_blank" rel="noopener noreferrer nofollow ugc">{children}</a>;
    },
  };

  const copy = () =>
    navigator.clipboard
      .writeText(`${m.content}\n\nSources:\n${m.sources.filter((s) => s.cited).map((s) => `[${s.n}] ${s.title} - ${window.location.origin}${s.link ?? ''}`).join('\n')}`)
      .then(() => toast.success('Answer copied'), () => toast.error('Could not copy'));

  const vote = (value: -1 | 0 | 1) => fb.mutate({ id: m.id, value }, { onError: (e) => toast.error(errorMessage(e)) });

  return (
    <article className="flex gap-3" aria-label="Community AI answer">
      <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-brand/15 text-brand"><Sparkles size={16} /></span>
      <div className="min-w-0 flex-1">
        {m.notice && (
          <div role="note" className="mb-2 flex gap-2 rounded-xl border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-text/90">
            <Info size={14} className="mt-0.5 shrink-0 text-warning" /> {m.notice}
          </div>
        )}
        <div className="prose-howlers text-[15px]">
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={components} skipHtml>
            {linkCitations(m.content, m.id, m.sources.length)}
          </ReactMarkdown>
        </div>

        {m.sources.length > 0 && (
          <div className="mt-4">
            <div className="mb-2 flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-muted">
              Sources
              <span className="font-normal normal-case tracking-normal text-faint">
                {cited.length ? `${cited.length} cited of ${m.sources.length} retrieved` : `${m.sources.length} retrieved`}
              </span>
              {cited.length > 0 && cited.length < m.sources.length && (
                <button type="button" onClick={() => setShowAll((v) => !v)} className="ml-auto font-semibold normal-case tracking-normal text-brand hover:underline">
                  {showAll ? 'Show cited only' : 'Show all'}
                </button>
              )}
            </div>
            <div className="grid gap-2 sm:grid-cols-2">
              {visible.map((s) => <SourceCard key={s.n} s={s} messageId={m.id} active={active === s.n} />)}
            </div>
          </div>
        )}

        {m.followUps.length > 0 && (
          <div className="mt-4 flex flex-wrap gap-2">
            {m.followUps.map((f) => (
              <button key={f} type="button" onClick={() => onFollowUp(f)} className="rounded-full border border-border px-3 py-1.5 text-left text-xs font-medium text-text/90 hover:border-brand/60 hover:bg-brand/10">
                {f}
              </button>
            ))}
          </div>
        )}

        <div className="mt-3 flex items-center gap-1 text-xs text-faint">
          <button type="button" onClick={copy} className="inline-flex h-8 items-center gap-1.5 rounded-full px-2.5 font-semibold text-muted hover:bg-surface-2 hover:text-text">
            <Copy size={14} /> Copy
          </button>
          <button type="button" aria-label="Helpful" aria-pressed={m.feedback === 1} onClick={() => vote(m.feedback === 1 ? 0 : 1)} className={cn('inline-flex h-8 w-8 items-center justify-center rounded-full hover:bg-surface-2', m.feedback === 1 ? 'text-accent' : 'text-muted')}>
            <ThumbsUp size={14} />
          </button>
          <button type="button" aria-label="Not helpful" aria-pressed={m.feedback === -1} onClick={() => vote(m.feedback === -1 ? 0 : -1)} className={cn('inline-flex h-8 w-8 items-center justify-center rounded-full hover:bg-surface-2', m.feedback === -1 ? 'text-danger' : 'text-muted')}>
            <ThumbsDown size={14} />
          </button>
          {m.engine && <span className="ml-auto truncate">{m.engine}</span>}
        </div>
      </div>
    </article>
  );
}
