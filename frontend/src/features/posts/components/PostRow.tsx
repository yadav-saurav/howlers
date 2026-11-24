import { ExternalLink, FileText, Image as ImageIcon, MessageSquare, Share2 } from 'lucide-react';
import { memo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Badge } from '@/components/ui/Display';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { PostSummary } from '@/types/api';
import { compactNumber } from '@/utils/format';
import { usePostVote } from '../hooks';
import { postPath } from '../paths';
import { PostMeta } from './PostCard';
import { copyPostLink, PostMenu } from './PostMenu';
import { VoteControl } from './VoteControl';

function Thumb({ post }: { post: PostSummary }) {
  const img = post.media[0];
  const box = 'flex h-14 w-[72px] shrink-0 items-center justify-center overflow-hidden rounded-lg border border-border bg-surface-2 text-faint';
  if (img) return <img src={img.thumbnails?.w320 ?? img.url} alt="" loading="lazy" decoding="async" className={`${box} object-cover`} />;
  if (post.type === 'LINK') return <span className={box}><ExternalLink size={18} /></span>;
  if (post.type === 'IMAGE') return <span className={box}><ImageIcon size={18} /></span>;
  return <span className={box}><FileText size={18} /></span>;
}

/** Compact view: dense one-line rows with a vote rail and a small thumbnail. */
export const PostRow = memo(function PostRow({ post, showCommunity = true }: { post: PostSummary; showCommunity?: boolean }) {
  const navigate = useNavigate();
  const vote = usePostVote(post.id);
  const href = postPath(post);

  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions -- mouse shortcut only: the title is a real link, which is what keyboard and screen-reader users use
    <article
      className="group flex cursor-pointer items-center gap-3 rounded-xl px-2 py-2 transition-colors hover:bg-surface-2/50"
      onClick={(e) => {
        if ((e.target as HTMLElement).closest('a,button,input,label,[role=menu]')) return;
        navigate(href);
      }}
      aria-labelledby={`post-${post.id}-title`}
    >
      <VoteControl
        orientation="vertical"
        size="sm"
        score={post.score}
        myVote={post.myVote}
        onVote={(v) => vote.mutate(v, { onError: (e) => toast.error(errorMessage(e)) })}
        label={post.title}
        disabled={post.locked}
      />
      <Thumb post={post} />
      <div className="min-w-0 flex-1">
        <h2 id={`post-${post.id}-title`} className="line-clamp-2 text-[15px] font-semibold leading-snug">
          <Link to={href}>{post.title}</Link>
          {post.flair && <Badge color={post.flair.color} className="ml-2 rounded-full px-2 align-middle">{post.flair.text}</Badge>}
        </h2>
        <div className="mt-1 flex items-center gap-3">
          <PostMeta post={post} showCommunity={showCommunity} />
          <div className="ml-auto flex shrink-0 items-center gap-1 text-xs text-muted">
            <Link to={`${href}#comments`} className="inline-flex h-7 items-center gap-1 rounded-full px-2 hover:bg-surface-2 hover:text-text">
              <MessageSquare size={14} /> {compactNumber(post.commentCount)}
            </Link>
            <button type="button" onClick={() => copyPostLink(post)} aria-label="Copy link" className="hidden h-7 items-center rounded-full px-2 hover:bg-surface-2 hover:text-text sm:inline-flex">
              <Share2 size={14} />
            </button>
            <PostMenu post={post} canModerate={!!post.viewer?.canModerate} />
          </div>
        </div>
      </div>
    </article>
  );
});
