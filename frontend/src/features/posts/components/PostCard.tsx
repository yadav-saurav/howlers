import { Bookmark, BookmarkCheck, ExternalLink, Lock, MessageSquare, Pin, Share2, ShieldAlert } from 'lucide-react';
import { memo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Avatar, Badge } from '@/components/ui/Display';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { errorMessage } from '@/services/apiError';
import type { PostSummary } from '@/types/api';
import { compactNumber, timeAgo } from '@/utils/format';
import { usePostVote, useSavePost } from '../hooks';
import { postPath } from '../paths';
import { ActionPill } from './ActionPill';
import { PollView } from './PollView';
import { copyPostLink, PostMenu } from './PostMenu';
import { FEED_IMAGE_SIZES, responsiveImage } from '@/utils/images';
import { VoteControl } from './VoteControl';

/** "c/slug • 5h" line; shows the author instead when we're already inside the community. */
export function PostMeta({ post, showCommunity }: { post: PostSummary; showCommunity: boolean }) {
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-x-1.5 text-xs text-muted">
      {showCommunity ? (
        <Link to={`/c/${post.community.slug}`} className="inline-flex items-center gap-1.5 font-semibold text-text hover:underline">
          <Avatar src={post.community.iconUrl} name={post.community.slug} size="sm" />
          c/{post.community.slug}
        </Link>
      ) : post.author ? (
        <Link to={`/u/${post.author.username}`} className="inline-flex items-center gap-1.5 font-semibold text-text hover:underline">
          <Avatar src={post.author.avatarUrl} name={post.author.username} size="sm" />
          u/{post.author.username}
        </Link>
      ) : (
        <span className="italic text-faint">[deleted]</span>
      )}
      <span aria-hidden="true">•</span>
      <time dateTime={post.createdAt} title={new Date(post.createdAt).toLocaleString()}>{timeAgo(post.createdAt)}</time>
      {showCommunity && post.author && (
        <>
          <span aria-hidden="true">•</span>
          <Link to={`/u/${post.author.username}`} className="text-faint hover:text-text hover:underline">u/{post.author.username}</Link>
        </>
      )}
      {post.editedAt && <span className="text-faint">(edited)</span>}
      {post.pinned && <Badge tone="accent"><Pin size={11} /> Pinned</Badge>}
      {post.locked && <Badge tone="warning"><Lock size={11} /> Locked</Badge>}
      {post.pending && <Badge tone="warning"><ShieldAlert size={11} /> Awaiting review</Badge>}
    </div>
  );
}

/** Large media / link preview shown in card view. */
function CardMedia({ post }: { post: PostSummary }) {
  const img = post.media[0];
  if (img) {
    return (
      <div className="relative mt-2 overflow-hidden rounded-2xl border border-border bg-black">
        <img
          {...responsiveImage(img)}
          sizes={FEED_IMAGE_SIZES}
          alt=""
          loading="lazy"
          decoding="async"
          width={img.width ?? undefined}
          height={img.height ?? undefined}
          className="mx-auto max-h-[540px] w-full object-contain"
        />
        {post.media.length > 1 && (
          <span className="absolute right-3 top-3 rounded-full bg-black/70 px-2 py-0.5 text-xs font-semibold text-white">
            1/{post.media.length}
          </span>
        )}
      </div>
    );
  }
  if (post.type === 'LINK' && post.url) {
    return (
      <a
        href={post.url}
        target="_blank"
        rel="noopener noreferrer nofollow ugc"
        onClick={(e) => e.stopPropagation()}
        className="mt-2 flex items-center gap-3 rounded-2xl border border-border bg-surface-2/60 px-4 py-3 hover:bg-surface-2"
      >
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-surface-3 text-muted">
          <ExternalLink size={18} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-semibold">{post.linkDomain}</span>
          <span className="block truncate text-xs text-muted">{post.url}</span>
        </span>
      </a>
    );
  }
  return null;
}

/**
 * Card view: full-width post with large media and pill actions. The whole card opens the post;
 * inner links and controls keep their own behaviour.
 */
export const PostCard = memo(function PostCard({ post, showCommunity = true }: { post: PostSummary; showCommunity?: boolean }) {
  const navigate = useNavigate();
  const { isAuthed } = useAuth();
  const vote = usePostVote(post.id);
  const save = useSavePost(post.id);
  const href = postPath(post);

  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions -- mouse shortcut only: the title is a real link, which is what keyboard and screen-reader users use
    <article
      className="group relative cursor-pointer rounded-2xl px-3 py-2.5 transition-colors hover:bg-surface-2/50 sm:px-4"
      onClick={(e) => {
        if ((e.target as HTMLElement).closest('a,button,input,label,[role=menu]')) return;
        navigate(href);
      }}
      aria-labelledby={`post-${post.id}-title`}
    >
      <div className="flex items-start justify-between gap-2">
        <PostMeta post={post} showCommunity={showCommunity} />
        <div className="-mr-1 -mt-1 opacity-70 group-hover:opacity-100">
          <PostMenu post={post} canModerate={!!post.viewer?.canModerate} />
        </div>
      </div>

      <h2 id={`post-${post.id}-title`} className="mt-1 text-lg font-semibold leading-snug text-text">
        <Link to={href}>{post.title}</Link>
      </h2>

      {(post.flair || post.tags.length > 0) && (
        <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
          {post.flair && <Badge color={post.flair.color} className="rounded-full px-2">{post.flair.text}</Badge>}
          {post.tags.map((t) => (
            <Link key={t} to={`/search?q=${encodeURIComponent(t)}`} className="text-xs text-faint hover:text-muted">#{t}</Link>
          ))}
        </div>
      )}

      {post.bodyPreview && post.type !== 'POLL' && post.media.length === 0 && (
        <p className="mt-1.5 line-clamp-3 text-sm leading-relaxed text-muted">{post.bodyPreview}</p>
      )}

      <CardMedia post={post} />

      {post.poll && (
        <div className="mt-2.5 max-w-xl">
          <PollView postId={post.id} poll={post.poll} compact />
        </div>
      )}

      <div className="mt-2.5 flex flex-wrap items-center gap-2">
        <VoteControl
          score={post.score}
          myVote={post.myVote}
          onVote={(v) => vote.mutate(v, { onError: (e) => toast.error(errorMessage(e)) })}
          label={post.title}
          disabled={post.locked}
        />
        <ActionPill to={`${href}#comments`} label={`${post.commentCount} comments`}>
          <MessageSquare size={15} /> {compactNumber(post.commentCount)}
        </ActionPill>
        {isAuthed && (
          <ActionPill
            pressed={post.saved}
            active={post.saved}
            onClick={() => save.mutate(!post.saved, { onError: (e) => toast.error(errorMessage(e)) })}
          >
            {post.saved ? <BookmarkCheck size={15} /> : <Bookmark size={15} />}
            <span className="sr-only sm:not-sr-only">{post.saved ? 'Saved' : 'Save'}</span>
          </ActionPill>
        )}
        <ActionPill onClick={() => copyPostLink(post)}>
          <Share2 size={15} /> <span className="sr-only sm:not-sr-only">Share</span>
        </ActionPill>
      </div>
    </article>
  );
});
