import { CalendarDays, MessageSquare, Settings, UserX } from 'lucide-react';
import { BlockButton } from '@/features/moderation/components/BlockControls';
import { MuteUserButton } from '@/features/mutes/components/MuteControls';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { Button, buttonClasses } from '@/components/ui/Button';
import { Avatar, Badge, Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { Segmented } from '@/components/ui/Tabs';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { Markdown } from '@/features/comments/components/Markdown';
import { MessageButton } from '@/features/messages/components/MessageButton';
import { PostList } from '@/features/posts/components/PostList';
import { ModeratorRecord } from '@/features/reputation/components/ModeratorRecord';
import { TopicStandings } from '@/features/reputation/components/TopicStandings';
import { useFollow, useProfile, useUserComments, useUserPosts } from '@/features/users/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns } from '@/layouts/PageColumns';
import { ApiError, errorMessage } from '@/services/apiError';
import type { PublicProfile } from '@/types/api';
import { compactNumber, displayName, formatDate, timeAgo } from '@/utils/format';

type Tab = 'posts' | 'comments' | 'about';

function Header({ p }: { p: PublicProfile }) {
  const { isAuthed } = useAuth();
  const follow = useFollow(p.username);
  return (
    <div className="mb-4 overflow-hidden border-b border-border bg-surface sm:rounded-lg sm:border">
      <div
        className="h-24 bg-surface-2 sm:h-32"
        style={p.bannerUrl ? { backgroundImage: `url(${p.bannerUrl})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
        aria-hidden="true"
      />
      <div className="flex flex-wrap items-end gap-4 px-4 pb-4">
        <Avatar src={p.avatarUrl} name={p.username} size="xl" className="-mt-10 border-4 border-surface" />
        <div className="min-w-0 flex-1">
          <h1 className="truncate font-serif text-2xl font-semibold">{displayName(p)}</h1>
          <p className="text-sm text-muted">u/{p.username}</p>
        </div>
        {p.isSelf ? (
          <Link to="/settings" className={buttonClasses('outline', 'sm')}><Settings size={15} /> Edit profile</Link>
        ) : isAuthed ? (
          <div className="flex items-center gap-2">
          <MuteUserButton username={p.username} />
          <BlockButton username={p.username} isBlocked={!!p.isBlocked} />
          {p.canMessage && <MessageButton username={p.username} />}
          <Button
            size="sm"
            variant={p.isFollowing ? 'outline' : 'primary'}
            loading={follow.isPending}
            onClick={() => follow.mutate(!p.isFollowing, { onError: (e) => toast.error(errorMessage(e)) })}
          >
            {p.isFollowing ? 'Following' : 'Follow'}
          </Button>
          </div>
        ) : (
          <Link to={`/login?next=/u/${p.username}`} className={buttonClasses('primary', 'sm')}>Follow</Link>
        )}
      </div>
      {p.bio && <p className="px-4 pb-3 text-sm leading-relaxed text-muted">{p.bio}</p>}
      <dl className="flex flex-wrap gap-x-6 gap-y-2 border-t border-border px-4 py-3 text-sm">
        <div><dt className="text-xs text-faint">Post karma</dt><dd className="font-semibold tabular-nums">{compactNumber(p.postKarma)}</dd></div>
        <div><dt className="text-xs text-faint">Comment karma</dt><dd className="font-semibold tabular-nums">{compactNumber(p.commentKarma)}</dd></div>
        <div><dt className="text-xs text-faint">Followers</dt><dd className="font-semibold tabular-nums">{compactNumber(p.followerCount)}</dd></div>
        <div><dt className="text-xs text-faint">Following</dt><dd className="font-semibold tabular-nums">{compactNumber(p.followingCount)}</dd></div>
        <div><dt className="text-xs text-faint">Joined</dt><dd className="font-semibold">{formatDate(p.createdAt)}</dd></div>
      </dl>
    </div>
  );
}

function CommentsTab({ username }: { username: string }) {
  const q = useUserComments(username);
  if (q.isPending) return <div className="space-y-3 p-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-16" />)}</div>;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const items = q.data.pages.flatMap((p) => p.items);
  if (!items.length) return <EmptyState icon={MessageSquare} title="No comments yet" />;
  return (
    <>
      <ul className="divide-y divide-border">
        {items.map(({ comment, post }) => (
          <li key={comment.id} className="px-4 py-3">
            <div className="text-xs text-muted">
              <Link to={`/c/${post.community.slug}/p/${post.id}#c-${comment.id}`} className="font-medium text-text hover:underline">{post.title}</Link>
              <span> · c/{post.community.slug} · {timeAgo(comment.createdAt)} · {comment.score} points</span>
            </div>
            <div className="mt-1">{comment.deleted ? <span className="text-sm italic text-faint">[deleted]</span> : <Markdown source={comment.body} />}</div>
          </li>
        ))}
      </ul>
      <LoadMore hasMore={!!q.hasNextPage} loading={q.isFetchingNextPage} onLoad={() => q.fetchNextPage()} />
    </>
  );
}

function AboutTab({ p }: { p: PublicProfile }) {
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title="About" />
        <div className="space-y-2 p-4 text-sm text-muted">
          {p.bio ? <p>{p.bio}</p> : <p>No bio yet.</p>}
          <p className="flex items-center gap-2"><CalendarDays size={14} /> Member since {formatDate(p.createdAt)}</p>
          <p className="text-xs text-faint">Karma reflects votes from other members. It is not a credential.</p>
        </div>
      </Card>
      <TopicStandings username={p.username} isSelf={!!p.isSelf} />
      <ModeratorRecord username={p.username} isSelf={!!p.isSelf} />
      {p.moderatorOf.length > 0 && (
        <Card>
          <CardHeader title="Moderates" />
          <div className="flex flex-wrap gap-2 p-4">
            {p.moderatorOf.map((c) => <Link key={c.id} to={`/c/${c.slug}`}><Badge tone="accent">c/{c.slug}</Badge></Link>)}
          </div>
        </Card>
      )}
      {p.communities.length > 0 && (
        <Card>
          <CardHeader title="Communities" />
          <div className="flex flex-wrap gap-2 p-4">
            {p.communities.map((c) => <Link key={c.id} to={`/c/${c.slug}`}><Badge>c/{c.slug}</Badge></Link>)}
          </div>
        </Card>
      )}
    </div>
  );
}

export default function ProfilePage() {
  const { username = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'posts';
  const q = useProfile(username);
  const posts = useUserPosts(username, tab === 'posts' && q.isSuccess);
  useDocumentTitle(q.data ? `${displayName(q.data)} (u/${q.data.username})` : `u/${username}`);

  if (q.isPending) return <PageColumns><Skeleton className="h-56 sm:rounded-lg" /></PageColumns>;
  if (q.isError) {
    return (
      <PageColumns>
        {q.error instanceof ApiError && q.error.status === 404 ? (
          <EmptyState icon={UserX} title={`u/${username} isn’t here`} description="This account doesn’t exist or was deleted." />
        ) : (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        )}
      </PageColumns>
    );
  }
  const p = q.data;
  return (
    <PageColumns rail={<AboutTab p={p} />}>
      <Header p={p} />
      <div className="mb-3 px-3 sm:px-0">
        <Segmented<Tab>
          label="Profile sections"
          value={tab}
          onChange={(v) => setParams({ tab: v }, { replace: true })}
          options={[
            { value: 'posts', label: 'Posts' },
            { value: 'comments', label: 'Comments' },
            { value: 'about', label: 'About' },
          ]}
        />
      </div>
      {tab === 'posts' && <PostList query={posts} empty={{ title: 'No posts yet', description: p.isSelf ? 'Your posts will appear here.' : undefined }} />}
      {tab === 'comments' && <Card className="rounded-none border-x-0 sm:rounded-lg sm:border-x"><CommentsTab username={p.username} /></Card>}
      {tab === 'about' && <div className="px-4 sm:px-0"><AboutTab p={p} /></div>}
    </PageColumns>
  );
}
