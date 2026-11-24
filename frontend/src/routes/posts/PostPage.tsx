import { ArrowLeft, Bookmark, BookmarkCheck, ExternalLink, FileX, Lock, MessageSquare, Pin, Share2, Sparkles } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Avatar, Badge, Card, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { CommentSection } from '@/features/comments/components/CommentSection';
import { DiscussionTabs } from '@/features/insight/components/DiscussionTabs';
import { AboutCard, RulesCard } from '@/features/communities/components/CommunitySidebar';
import { useCommunity } from '@/features/communities/hooks';
import { usePostLive } from '@/features/live/usePostLive';
import { ReactionBar } from '@/features/reactions/components/ReactionBar';
import { EditPostDialog } from '@/features/posts/components/EditPostDialog';
import { ActionPill } from '@/features/posts/components/ActionPill';
import { PollView } from '@/features/posts/components/PollView';
import { useUiPrefs } from '@/store/uiPrefs';
import { copyPostLink, PostMenu } from '@/features/posts/components/PostMenu';
import { VoteControl } from '@/features/posts/components/VoteControl';
import { usePost, usePostVote, useSavePost } from '@/features/posts/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns } from '@/layouts/PageColumns';
import { ApiError, errorMessage } from '@/services/apiError';
import { FEED_IMAGE_SIZES, responsiveImage } from '@/utils/images';
import { cn, compactNumber, timeAgo } from '@/utils/format';

function PostSkeleton() {
  return (
    <Card className="space-y-3 p-5">
      <Skeleton className="h-3 w-56" />
      <Skeleton className="h-7 w-4/5" />
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-4 w-11/12" />
      <Skeleton className="h-4 w-2/3" />
    </Card>
  );
}

export default function PostPage() {
  const { id: rawId = '', slug } = useParams();
  const id = Number(rawId);
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const { isAuthed } = useAuth();
  const q = usePost(id);
  const community = useCommunity(q.data?.community.slug ?? slug ?? '');
  const vote = usePostVote(id);
  const save = useSavePost(id);
  // Subscribe only after the post loaded (so we know we may view it); anonymous users have no socket.
  usePostLive(id, isAuthed && q.isSuccess);
  const [editing, setEditing] = useState(params.get('edit') === '1');
  useDocumentTitle(q.data?.title);
  const visitPost = useUiPrefs((st) => st.visitPost);
  useEffect(() => {
    const d = q.data;
    if (!d || d.removed || !d.author) return;
    visitPost({ id: d.id, title: d.title, slug: d.community.slug, iconUrl: d.community.iconUrl, score: d.score, commentCount: d.commentCount, thumb: d.media[0]?.url ?? null });
  }, [q.data, visitPost]);

  useEffect(() => {
    if (q.data && window.location.hash.startsWith('#c-')) {
      window.setTimeout(() => document.getElementById(window.location.hash.slice(1))?.scrollIntoView({ block: 'center' }), 600);
    }
  }, [q.data]);

  const rail = community.data ? (
    <>
      <AboutCard c={community.data} />
      <RulesCard c={community.data} />
    </>
  ) : undefined;

  if (!Number.isFinite(id)) {
    return <PageColumns><EmptyState icon={FileX} title="Post not found" /></PageColumns>;
  }
  if (q.isPending) return <PageColumns rail={rail}><PostSkeleton /></PageColumns>;
  if (q.isError) {
    const status = q.error instanceof ApiError ? q.error.status : 0;
    return (
      <PageColumns>
        {status === 404 || status === 403 ? (
          <EmptyState
            icon={FileX}
            title={status === 404 ? 'This post doesn’t exist' : 'You can’t view this post'}
            description={status === 404 ? 'It may have been deleted.' : 'It belongs to a private community.'}
            action={<Link to="/home" className={buttonClasses('primary', 'sm')}>Back to home</Link>}
          />
        ) : (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        )}
      </PageColumns>
    );
  }

  const p = q.data;
  const back = () => (window.history.length > 1 ? navigate(-1) : navigate(`/c/${p.community.slug}`));

  return (
    <PageColumns rail={rail}>
      <div className="px-3 pt-2 sm:px-0">
        <article aria-labelledby="post-title" className="px-1 sm:px-2">
          <div className="flex items-center gap-2">
            <button type="button" onClick={back} aria-label="Back" className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-surface-2 hover:bg-surface-3">
              <ArrowLeft size={17} />
            </button>
            <Avatar src={p.community.iconUrl} name={p.community.slug} size="md" />
            <div className="min-w-0 text-xs leading-tight">
              <div className="flex flex-wrap items-center gap-x-1.5">
                <Link to={`/c/${p.community.slug}`} className="inline-flex min-h-6 items-center font-semibold text-text hover:underline">c/{p.community.slug}</Link>
                <span className="text-muted" aria-hidden="true">•</span>
                <time className="text-muted" dateTime={p.createdAt} title={new Date(p.createdAt).toLocaleString()}>{timeAgo(p.createdAt)}</time>
                {p.editedAt && <span className="text-faint">(edited {timeAgo(p.editedAt)})</span>}
                {p.pinned && <Badge tone="accent"><Pin size={11} /> Pinned</Badge>}
                {p.locked && <Badge tone="warning"><Lock size={11} /> Locked</Badge>}
              </div>
              <div className="mt-0.5 text-muted">
                {p.author ? <Link to={`/u/${p.author.username}`} className="inline-flex min-h-6 items-center hover:text-text hover:underline">u/{p.author.username}</Link> : <span className="italic">[deleted]</span>}
              </div>
            </div>
            <div className="ml-auto">
              <PostMenu post={p} canModerate={p.viewer.canModerate} onEdit={p.viewer.canEdit ? () => setEditing(true) : undefined} afterDelete={() => navigate(`/c/${p.community.slug}`)} />
            </div>
          </div>
          <div className="min-w-0">
            <h1 id="post-title" className="mt-3 text-2xl font-bold leading-tight tracking-tight sm:text-[26px]">{p.title}</h1>
            {(p.flair || p.tags.length > 0) && (
              <div className="mt-2 flex flex-wrap items-center gap-1.5">
                {p.flair && <Badge color={p.flair.color} className="rounded-full px-2">{p.flair.text}</Badge>}
                {p.tags.map((t) => <Link key={t} to={`/search?q=${encodeURIComponent(`#${t}`)}`} className="text-xs text-faint hover:text-muted">#{t}</Link>)}
              </div>
            )}

            {p.pending ? (
              <p role="status" className="mt-4 rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-sm text-warning">
                {p.viewer.canModerate
                  ? 'Held by the automatic spam checks or a community automod rule. Only the author and moderators can see it. Approve it from the menu or the mod queue to publish it.'
                  : 'Your post is waiting for a moderator to review it. Automatic checks (spam or this community’s automod rules) held it; only you and the moderators can see it for now. Your notifications say why.'}
              </p>
            ) : (
              p.removed && <p className="mt-4 rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">This post was removed by moderators.</p>
            )}

            {p.type === 'LINK' && p.url && (
              <a href={p.url} target="_blank" rel="noopener noreferrer nofollow ugc" className="mt-4 flex items-center gap-3 rounded-md border border-border bg-surface-2/50 px-3 py-2.5 text-sm hover:border-accent/50">
                <ExternalLink size={16} className="shrink-0 text-muted" />
                <span className="min-w-0 flex-1 truncate text-accent">{p.url}</span>
                <span className="shrink-0 text-xs text-faint">{p.linkDomain}</span>
              </a>
            )}

            {p.media.length > 0 && (
              <div className={cn('mt-4 grid gap-2', p.media.length > 1 && 'sm:grid-cols-2')}>
                {p.media.map((m) => (
                  <a key={m.id} href={m.url} target="_blank" rel="noopener noreferrer">
                    <img {...responsiveImage(m)} sizes={FEED_IMAGE_SIZES} alt="" loading="lazy" decoding="async" width={m.width ?? undefined} height={m.height ?? undefined} className="max-h-[560px] w-full rounded-md border border-border bg-surface-2 object-contain" />
                  </a>
                ))}
              </div>
            )}

            {p.bodyHtml && (
              // bodyHtml is sanitized server-side (allow-list) before storage; see docs/API.md
              <div className="prose-howlers mt-4" dangerouslySetInnerHTML={{ __html: p.bodyHtml }} />
            )}

            {p.poll && <div className="mt-4 max-w-xl"><PollView postId={p.id} poll={p.poll} /></div>}

            {!p.pending && !p.removed && <div className="mt-4"><ReactionBar type="POST" id={p.id} /></div>}

            <div className="mt-4 flex flex-wrap items-center gap-2">
              <VoteControl score={p.score} myVote={p.myVote} label={p.title} disabled={p.locked || p.removed} onVote={(v) => vote.mutate(v, { onError: (e) => toast.error(errorMessage(e)) })} />
              <ActionPill label={`${compactNumber(p.commentCount)} comments`} onClick={() => document.getElementById('comments')?.scrollIntoView({ behavior: 'smooth' })}>
                <MessageSquare size={15} /> {compactNumber(p.commentCount)}
              </ActionPill>
              {isAuthed && (
                <ActionPill pressed={p.saved} active={p.saved} onClick={() => save.mutate(!p.saved, { onSuccess: () => toast.success(p.saved ? 'Removed from saved' : 'Saved'), onError: (e) => toast.error(errorMessage(e)) })}>
                  {p.saved ? <BookmarkCheck size={15} /> : <Bookmark size={15} />} {p.saved ? 'Saved' : 'Save'}
                </ActionPill>
              )}
              <ActionPill onClick={() => copyPostLink(p)}>
                <Share2 size={15} /> Share
              </ActionPill>
              {isAuthed && !p.removed && (
                <ActionPill to={`/ai?post=${p.id}`} label="Ask AI about this thread">
                  <Sparkles size={15} /> Ask AI
                </ActionPill>
              )}
            </div>
          </div>
        </article>
      </div>

      <div className="mt-6 px-4 sm:px-2">
        <DiscussionTabs postId={p.id} commentCount={p.commentCount} comments={<CommentSection postId={p.id} locked={p.locked} commentCount={p.commentCount} opId={p.author?.id ?? null} canModerate={p.viewer.canModerate} communitySlug={p.community.slug} />} />
      </div>

      {editing && p.viewer.canEdit && (
        <EditPostDialog
          post={p}
          flairs={community.data?.flairs ?? []}
          open={editing}
          onOpenChange={(o) => {
            setEditing(o);
            if (!o && params.has('edit')) {
              const n = new URLSearchParams(params);
              n.delete('edit');
              setParams(n, { replace: true });
            }
          }}
        />
      )}
    </PageColumns>
  );
}
