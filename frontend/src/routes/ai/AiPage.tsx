import { ArrowUp, CalendarRange, FileText, MessageSquarePlus, Sparkles, Trash2, Users, X } from 'lucide-react';
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Skeleton } from '@/components/ui/Display';
import { ErrorState } from '@/components/ui/States';
import { Spinner } from '@/components/ui/Spinner';
import { toast } from '@/components/ui/Toast';
import { useAiConversation, useAiConversations, useAiStatus, useAsk, useDeleteConversation } from '@/features/ai/api';
import { AnswerView } from '@/features/ai/components/AnswerView';
import { useMyCommunities } from '@/features/communities/hooks';
import { usePost } from '@/features/posts/hooks';
import { SortMenu } from '@/features/posts/components/FeedControls';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { errorMessage } from '@/services/apiError';
import { cn, timeAgo } from '@/utils/format';

const SUGGESTED = [
  'What is the community’s recommended roadmap for learning cybersecurity?',
  'What do developers think about Rust compared with Go?',
  'What mistakes do beginners repeatedly make?',
  'Which discussions have conflicting opinions about AI coding tools?',
  'What resources have people found useful for system design?',
  'What do people say about switching careers into tech?',
];

const RANGES = [
  { value: 'any', label: 'Any time' },
  { value: '30', label: 'Past month' },
  { value: '180', label: 'Past 6 months' },
  { value: '365', label: 'Past year' },
];

/** Community AI: answers grounded in Howlers discussions, with numbered citations to real posts and comments. */
export default function AiPage() {
  useDocumentTitle('Community AI');
  const [params, setParams] = useSearchParams();
  const conversationId = params.get('c') ? Number(params.get('c')) : null;
  const scopeCommunity = params.get('community') ?? '';
  const scopePost = params.get('post') ? Number(params.get('post')) : null;
  const [range, setRange] = useState('any');
  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState<string | null>(null);
  const status = useAiStatus();
  const list = useAiConversations();
  const conv = useAiConversation(conversationId);
  const mine = useMyCommunities();
  const scopedPost = usePost(scopePost ?? NaN);
  const ask = useAsk();
  const del = useDeleteConversation();
  const bottom = useRef<HTMLDivElement>(null);

  const messages = conv.data?.messages ?? [];
  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages.length, pending]);

  const setParam = (k: string, v: string | null) => {
    const n = new URLSearchParams(params);
    if (v) n.set(k, v); else n.delete(k);
    setParams(n, { replace: true });
  };

  const send = (text: string) => {
    const question = text.trim();
    if (!question || ask.isPending) return;
    setPending(question);
    setDraft('');
    ask.mutate(
      {
        conversationId: conversationId ?? undefined,
        question,
        communitySlug: scopeCommunity || undefined,
        postId: scopePost ?? undefined,
        sinceDays: range === 'any' ? undefined : Number(range),
      },
      {
        onSuccess: (r) => {
          if (r.conversation.id !== conversationId) setParam('c', String(r.conversation.id));
        },
        onError: (e) => {
          toast.error(errorMessage(e));
          setDraft(question);
        },
        onSettled: () => setPending(null),
      },
    );
  };

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send(draft);
    }
  };

  const newChat = () => {
    const n = new URLSearchParams();
    if (scopeCommunity) n.set('community', scopeCommunity);
    setParams(n, { replace: true });
  };

  const empty = !conversationId && !pending;

  return (
    <div className="mx-auto flex h-[calc(100vh-56px)] w-full max-w-[1200px]">
      {/* history */}
      <aside className="hidden w-64 shrink-0 flex-col border-r border-border p-3 md:flex">
        <button type="button" onClick={newChat} className="mb-3 inline-flex h-10 items-center justify-center gap-2 rounded-full bg-brand text-sm font-semibold text-brand-fg hover:bg-brand/90">
          <MessageSquarePlus size={16} /> New chat
        </button>
        <div className="px-2 pb-1 text-xs font-semibold uppercase tracking-wider text-muted">History</div>
        <div className="flex-1 space-y-0.5 overflow-y-auto">
          {list.isPending && Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-9" />)}
          {list.data?.length === 0 && <p className="px-2 py-2 text-xs text-faint">Your conversations appear here.</p>}
          {list.data?.map((c) => (
            <div key={c.id} className={cn('group flex items-center rounded-lg', conversationId === c.id ? 'bg-surface-2' : 'hover:bg-surface-2/60')}>
              <button type="button" onClick={() => setParams({ c: String(c.id) }, { replace: true })} className="min-w-0 flex-1 px-2.5 py-2 text-left">
                <span className="block truncate text-sm">{c.title}</span>
                <span className="block text-[11px] text-faint">{c.communitySlug ? `c/${c.communitySlug} · ` : ''}{timeAgo(c.updatedAt)}</span>
              </button>
              <button
                type="button"
                aria-label={`Delete conversation ${c.title}`}
                onClick={() => del.mutate(c.id, { onSuccess: () => { if (conversationId === c.id) newChat(); toast.success('Conversation deleted'); }, onError: (e) => toast.error(errorMessage(e)) })}
                className="mr-1 hidden h-7 w-7 items-center justify-center rounded-full text-muted hover:bg-surface-3 hover:text-danger group-hover:inline-flex"
              >
                <Trash2 size={13} />
              </button>
            </div>
          ))}
        </div>
      </aside>

      {/* chat */}
      <section className="flex min-w-0 flex-1 flex-col">
        <div className="flex-1 overflow-y-auto px-4 py-6 sm:px-8">
          {empty && (
            <div className="mx-auto max-w-2xl pt-6 text-center">
              <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-brand/15 text-brand"><Sparkles size={24} /></span>
              <h1 className="mt-4 text-2xl font-bold">Ask the community</h1>
              <p className="mt-1 text-sm text-muted">Answers are built from Howlers discussions you can see, and every claim links to the post or comment it came from.</p>
              {status.data && (
                <p className={cn('mx-auto mt-3 inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs', status.data.llmConfigured ? 'bg-accent/10 text-accent' : 'bg-warning/10 text-warning')}>
                  {status.data.llmConfigured ? `Powered by ${status.data.model}` : status.data.note}
                </p>
              )}
              <div className="mt-6 grid gap-2 text-left sm:grid-cols-2">
                {SUGGESTED.map((s) => (
                  <button key={s} type="button" onClick={() => send(s)} className="rounded-xl border border-border px-4 py-3 text-sm hover:border-brand/50 hover:bg-surface-2/60">
                    {s}
                  </button>
                ))}
              </div>
            </div>
          )}

          {conv.isPending && conversationId != null && <div className="mx-auto max-w-3xl space-y-4"><Skeleton className="h-10 w-2/3" /><Skeleton className="h-40" /></div>}
          {conv.isError && <ErrorState error={conv.error} onRetry={() => conv.refetch()} />}

          <div className="mx-auto max-w-3xl space-y-8">
            {messages.map((m) =>
              m.role === 'USER' ? (
                <div key={m.id} className="flex justify-end">
                  <p className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-md bg-surface-3 px-4 py-2.5 text-[15px]">{m.content}</p>
                </div>
              ) : (
                <AnswerView key={m.id} m={m} conversationId={conversationId!} onFollowUp={send} />
              ),
            )}
            {pending && (
              <>
                <div className="flex justify-end">
                  <p className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-md bg-surface-3 px-4 py-2.5 text-[15px]">{pending}</p>
                </div>
                <div className="flex items-center gap-3 text-sm text-muted" role="status">
                  <span className="flex h-8 w-8 items-center justify-center rounded-full bg-brand/15 text-brand"><Spinner size={15} /></span>
                  Searching discussions and writing an answer…
                </div>
              </>
            )}
            <div ref={bottom} />
          </div>
        </div>

        {/* composer */}
        <div className="border-t border-border px-4 pb-4 pt-3 sm:px-8">
          <div className="mx-auto max-w-3xl">
            <div className="mb-2 flex flex-wrap items-center gap-1.5">
              <span className="inline-flex items-center gap-1 text-xs text-muted"><Users size={13} /></span>
              <SortMenu
                title="Search in"
                value={scopeCommunity || 'all'}
                onChange={(v) => setParam('community', v === 'all' ? null : v)}
                options={[{ value: 'all', label: 'All communities' }, ...(mine.data ?? []).map((c) => ({ value: c.slug, label: `c/${c.slug}` }))]}
              />
              <span className="inline-flex items-center gap-1 text-xs text-muted"><CalendarRange size={13} /></span>
              <SortMenu title="Time range" value={range} onChange={setRange} options={RANGES} />
              {scopePost != null && (
                <span className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-brand/15 px-3 py-1 text-xs font-semibold text-brand">
                  <FileText size={12} />
                  <span className="truncate">Thread: {scopedPost.data?.title ?? `post ${scopePost}`}</span>
                  <button type="button" aria-label="Remove thread scope" onClick={() => setParam('post', null)}><X size={12} /></button>
                </span>
              )}
              <Link to="/about" className="ml-auto hidden text-[11px] text-faint hover:text-muted sm:inline">AI answers can be wrong. Check the sources.</Link>
            </div>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                send(draft);
              }}
              className="flex items-end gap-2 rounded-2xl border border-border bg-surface-2/50 p-2 focus-within:border-brand/60"
            >
              <label htmlFor="ai-q" className="sr-only">Ask a question</label>
              <textarea
                id="ai-q"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={onKey}
                rows={1}
                maxLength={1000}
                placeholder={scopePost ? 'Ask about this thread…' : scopeCommunity ? `Ask c/${scopeCommunity}…` : 'Ask anything the community has discussed…'}
                className="max-h-40 min-h-[40px] flex-1 resize-none bg-transparent px-2 py-2 text-[15px] placeholder:text-muted focus:outline-none"
              />
              <button type="submit" aria-label="Send" disabled={!draft.trim() || ask.isPending} className="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-brand text-brand-fg disabled:opacity-40">
                {ask.isPending ? <Spinner size={16} /> : <ArrowUp size={18} />}
              </button>
            </form>
          </div>
        </div>
      </section>
    </div>
  );
}
