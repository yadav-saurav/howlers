import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { JoinButton } from '@/features/communities/components/JoinButton';
import { useTrendingCommunities } from '@/features/communities/hooks';
import { useTrendingPosts } from '@/features/posts/hooks';
import { postPath } from '@/features/posts/paths';
import { useAuth } from '@/features/auth/hooks';
import { useUiPrefs } from '@/store/uiPrefs';
import { compactNumber, pluralize } from '@/utils/format';
import { brand } from '@/design-system/brand';
import { sizedUrl } from '@/utils/images';
import { ActivePulsesRail } from '@/features/pulse/components/PulseSection';

/** Rail panels share one look: dark rounded panel, small uppercase heading. */
export function RailPanel({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="overflow-hidden rounded-xl bg-surface-2/60">
      <div className="flex items-center justify-between px-4 pb-1 pt-3">
        <h2 className="text-xs font-semibold uppercase tracking-wider text-muted">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function RecentPostsCard() {
  const recent = useUiPrefs((s) => s.recentPosts);
  const clear = useUiPrefs((s) => s.clearRecentPosts);
  if (!recent.length) return null;
  return (
    <RailPanel title="Recent posts" action={<button type="button" onClick={clear} className="text-xs font-semibold text-brand hover:underline">Clear</button>}>
      <ul className="divide-y divide-border/70 px-4 pb-2">
        {recent.map((p) => (
          <li key={p.id} className="flex gap-3 py-3">
            <div className="min-w-0 flex-1">
              <Link to={`/c/${p.slug}`} className="inline-flex items-center gap-1.5 text-xs text-muted hover:underline">
                <Avatar src={p.iconUrl} name={p.slug} size="xs" /> c/{p.slug}
              </Link>
              <Link to={`/c/${p.slug}/p/${p.id}`} className="mt-1 line-clamp-2 text-sm font-semibold leading-snug hover:underline">{p.title}</Link>
              <div className="mt-1 text-xs text-faint">{pluralize(p.score, 'upvote')} · {pluralize(p.commentCount, 'comment')}</div>
            </div>
            {p.thumb && <img src={sizedUrl(p.thumb, 320)} alt="" loading="lazy" decoding="async" className="h-16 w-16 shrink-0 rounded-lg object-cover" />}
          </li>
        ))}
      </ul>
    </RailPanel>
  );
}

export function TrendingPostsCard() {
  const q = useTrendingPosts();
  return (
    <RailPanel title="Trending today">
      {q.isPending && <div className="space-y-3 p-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-9" />)}</div>}
      {q.isError && <p className="px-4 py-3 text-sm text-muted">Trending is unavailable right now.</p>}
      {q.data && q.data.length === 0 && <p className="px-4 py-3 text-sm text-muted">Nothing trending yet.</p>}
      {q.data && q.data.length > 0 && (
        <ol className="px-4 pb-2">
          {q.data.map((p) => (
            <li key={p.id} className="py-2.5">
              <Link to={`/c/${p.community.slug}`} className="inline-flex items-center gap-1.5 text-xs text-muted hover:underline">
                <Avatar src={p.community.iconUrl} name={p.community.slug} size="xs" /> c/{p.community.slug}
              </Link>
              <Link to={postPath(p)} className="mt-0.5 line-clamp-2 block text-sm font-semibold leading-snug hover:underline">{p.title}</Link>
              <div className="mt-0.5 text-xs text-faint">{compactNumber(p.score)} upvotes · {compactNumber(p.commentCount)} comments</div>
            </li>
          ))}
        </ol>
      )}
    </RailPanel>
  );
}

export function TrendingCommunitiesCard() {
  const q = useTrendingCommunities();
  return (
    <RailPanel title="Popular communities" action={<Link to="/communities" className="text-xs font-semibold text-brand hover:underline">See more</Link>}>
      {q.isPending && <div className="space-y-3 p-4">{Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-9" />)}</div>}
      {q.isError && <p className="px-4 py-3 text-sm text-muted">Unavailable right now.</p>}
      {q.data && q.data.length === 0 && <p className="px-4 py-3 text-sm text-muted">No communities yet.</p>}
      {q.data && q.data.length > 0 && (
        <ul className="px-2 pb-2">
          {q.data.map((c) => (
            <li key={c.id} className="flex items-center gap-3 rounded-lg px-2 py-2 hover:bg-surface-2">
              <Avatar src={c.iconUrl} name={c.slug} size="md" />
              <Link to={`/c/${c.slug}`} className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">c/{c.slug}</span>
                <span className="block text-xs text-faint">{pluralize(c.memberCount, 'member')}</span>
              </Link>
              <JoinButton slug={c.slug} isMember={c.isMember} />
            </li>
          ))}
        </ul>
      )}
    </RailPanel>
  );
}

function JoinCard() {
  const { isAuthed } = useAuth();
  if (isAuthed) return null;
  return (
    <RailPanel title={`New to ${brand.name}?`}>
      <div className="px-4 pb-4">
        <p className="text-sm text-muted">Join to vote, comment, follow people and build your own feed.</p>
        <Link to="/register" className={buttonClasses('primary', 'md', 'mt-3 w-full')}>Create account</Link>
      </div>
    </RailPanel>
  );
}

export function RightRail() {
  return (
    <>
      <JoinCard />
      <ActivePulsesRail />
      <RecentPostsCard />
      <TrendingCommunitiesCard />
      <TrendingPostsCard />
    </>
  );
}
