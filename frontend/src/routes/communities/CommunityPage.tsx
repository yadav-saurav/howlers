import { Ban, Clock, Flame, Lock, Plus, Settings, ShieldAlert, ShieldCheck, Sparkles, TrendingUp, Trophy, UsersRound } from 'lucide-react';
import { MuteCommunityButton } from '@/features/mutes/components/MuteControls';
import { useEffect } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Avatar, Card, Skeleton } from '@/components/ui/Display';
import { PageSpinner } from '@/components/ui/Spinner';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Segmented } from '@/components/ui/Tabs';
import { useAuth } from '@/features/auth/hooks';
import { AboutCard, ModeratorsCard, RulesCard } from '@/features/communities/components/CommunitySidebar';
import { JoinButton } from '@/features/communities/components/JoinButton';
import { MembersList } from '@/features/communities/components/MembersList';
import { useModStats } from '@/features/moderation/api';
import { formatBanExpiry } from '@/features/moderation/helpers';
import { CommunityPulseRail, PulseSection } from '@/features/pulse/components/PulseSection';
import { useCommunity } from '@/features/communities/hooks';
import { FeedControls, SortMenu } from '@/features/posts/components/FeedControls';
import { PostList } from '@/features/posts/components/PostList';
import { CommunityPodcasts } from '@/features/podcasts/components/CommunityPodcasts';
import { FundingSection } from '@/features/funding/components/FundingSection';
import { useUiPrefs } from '@/store/uiPrefs';
import { NewPostsPill } from '@/features/live/NewPostsPill';
import { useNewPosts } from '@/features/live/useNewPosts';
import { qk } from '@/services/queryKeys';
import { useCommunityPosts } from '@/features/posts/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns } from '@/layouts/PageColumns';
import { ApiError } from '@/services/apiError';
import type { CommunityDetail, CommunitySort, TimeRange } from '@/types/api';

type View = 'posts' | 'about' | 'members' | 'pulse' | 'podcasts' | 'funding';

function Header({ c }: { c: CommunityDetail }) {
  const { isAuthed } = useAuth();
  const visit = useUiPrefs((st) => st.visitCommunity);
  useEffect(() => {
    if (c.viewer.canView) visit({ slug: c.slug, iconUrl: c.iconUrl });
  }, [c.slug, c.iconUrl, c.viewer.canView, visit]);
  return (
    <div className="mb-2">
      <div
        className="h-[88px] bg-surface-2 sm:mt-2 sm:h-[128px] sm:rounded-xl"
        style={c.bannerUrl ? { backgroundImage: `url(${c.bannerUrl})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
        aria-hidden="true"
      >
        {!c.bannerUrl && (
          <svg className="h-full w-full text-surface-3 sm:rounded-xl" preserveAspectRatio="none" viewBox="0 0 400 80" aria-hidden="true">
            <path d="M0 50 Q100 30 200 45 T400 35 V80 H0Z" fill="currentColor" opacity="0.6" />
            <path d="M0 64 Q120 48 220 60 T400 52 V80 H0Z" fill="currentColor" />
          </svg>
        )}
      </div>
      <div className="flex flex-wrap items-end gap-3 px-4 sm:px-4">
        <Avatar src={c.iconUrl} name={c.slug} size="xl" className="-mt-10 border-4 border-bg sm:-mt-12" />
        <div className="min-w-[200px] flex-1 pb-1">
          <h1 className="break-words text-2xl font-bold tracking-tight sm:text-[28px]">c/{c.slug}</h1>
          <p className="text-sm text-muted">
            {c.name}
            {c.visibility === 'PRIVATE' && <> · <Lock size={12} className="inline" /> Private</>}
            {c.visibility === 'RESTRICTED' && <> · Restricted</>}
          </p>
        </div>
        <div className="flex items-center gap-2 pb-1">
          {c.viewer.canPost && (
            <Link to={`/c/${c.slug}/submit`} className={buttonClasses('outline', 'md')}>
              <Plus size={18} /> Create Post
            </Link>
          )}
          {isAuthed && c.viewer.canView && (
            <Link to={`/ai?community=${c.slug}`} className={buttonClasses('secondary', 'md')} title={`Ask questions answered from c/${c.slug} discussions`}>
              <Sparkles size={16} /> Ask
            </Link>
          )}
          {!c.viewer.banned && (isAuthed || !c.viewer.isMember) && <JoinButton slug={c.slug} isMember={c.viewer.isMember} isOwner={c.viewer.role === 'OWNER'} size="md" />}
          {isAuthed && c.viewer.canView && <MuteCommunityButton slug={c.slug} />}
          {c.viewer.canModerate && <ModToolsLink slug={c.slug} />}
          {c.viewer.canModerate && (
            <Link to={`/c/${c.slug}/settings`} className={buttonClasses('secondary', 'md', 'w-10 px-0')} aria-label="Community settings" title="Community settings">
              <Settings size={18} />
            </Link>
          )}
        </div>
      </div>
      {c.status === 'QUARANTINED' && (
        <div role="status" className="mx-3 mt-3 flex items-start gap-2 rounded-xl border border-warning/40 bg-warning/10 px-4 py-3 text-sm sm:mx-0">
          <ShieldAlert size={16} className="mt-0.5 shrink-0 text-warning" />
          <span>
            This community is quarantined by the site admins{c.statusReason ? `: “${c.statusReason}”` : ''}. It doesn’t appear in Popular,
            All, trending or site search.
          </span>
        </div>
      )}
      {c.status === 'BANNED' && (
        <div role="status" className="mx-3 mt-3 flex items-start gap-2 rounded-xl border border-danger/40 bg-danger/10 px-4 py-3 text-sm sm:mx-0">
          <Ban size={16} className="mt-0.5 shrink-0 text-danger" />
          <span>
            This community was banned by the site admins{c.statusReason ? `: “${c.statusReason}”` : ''}.
            {c.viewer.canView ? ' You can see it because you are an admin.' : ' Its posts are no longer available.'}
          </span>
        </div>
      )}
      {c.viewer.banned && (
        <div role="status" className="mx-3 mt-3 flex items-center gap-2 rounded-xl border border-danger/40 bg-danger/10 px-4 py-3 text-sm sm:mx-0">
          <Ban size={16} className="shrink-0 text-danger" />
          <span>You're banned from c/{c.slug} {formatBanExpiry(c.viewer.bannedUntil ?? null)}. You can still read, but not post, comment or vote.</span>
        </div>
      )}
    </div>
  );
}

/** "Mod tools" pill with the open-report count. */
function ModToolsLink({ slug }: { slug: string }) {
  const stats = useModStats(slug);
  const open = stats.data?.openReports ?? 0;
  return (
    <Link to={`/c/${slug}/mod`} className={buttonClasses('secondary', 'md')} title="Moderation queue, appeals, bans and log">
      <ShieldCheck size={16} /> Mod tools
      {open > 0 && <span className="rounded-full bg-danger px-1.5 text-[11px] font-bold text-danger-fg">{open}</span>}
    </Link>
  );
}

const SORTS: { value: CommunitySort; label: string; icon: JSX.Element }[] = [
  { value: 'hot', label: 'Hot', icon: <Flame size={15} /> },
  { value: 'new', label: 'New', icon: <Clock size={15} /> },
  { value: 'rising', label: 'Rising', icon: <TrendingUp size={15} /> },
  { value: 'top', label: 'Top', icon: <Trophy size={15} /> },
];

const ALL = { kind: 'all' } as const;

function Posts({ c }: { c: CommunityDetail }) {
  const [params, setParams] = useSearchParams();
  const sort = (params.get('sort') as CommunitySort) || 'hot';
  const t = (params.get('t') as TimeRange) || 'week';
  const posts = useCommunityPosts(c.slug, sort, t, c.viewer.canView);
  const { isAuthed } = useAuth();
  // Only subscribe once the detail says we may view it: a rejected SUBSCRIBE closes the whole socket.
  const live = useNewPosts({
    destination: isAuthed && c.viewer.canView ? `/topic/community.${c.id}` : null,
    scope: ALL,
    queryKey: qk.communityPosts(c.slug, sort, t),
  });
  const set = (k: string, v: string) => {
    const n = new URLSearchParams(params);
    n.set(k, v);
    setParams(n, { replace: true });
  };
  return (
    <>
      <FeedControls>
        <SortMenu title="Sort by" value={sort} options={SORTS} onChange={(v) => set('sort', v)} />
        {sort === 'top' && (
          <SortMenu
            title="Top posts from"
            value={t}
            onChange={(v) => set('t', v)}
            options={[
              { value: 'day', label: 'Today' },
              { value: 'week', label: 'This week' },
              { value: 'month', label: 'This month' },
              { value: 'year', label: 'This year' },
              { value: 'all', label: 'All time' },
            ]}
          />
        )}
      </FeedControls>
      <NewPostsPill count={live.count} onShow={live.showNew} />
      <PostList
        query={posts}
        showCommunity={false}
        empty={{
          title: 'No posts yet',
          description: c.viewer.canPost ? 'Start the first discussion in this community.' : 'Check back soon.',
          action: c.viewer.canPost ? <Link to={`/c/${c.slug}/submit`} className={buttonClasses('primary', 'sm')}>Create a post</Link> : undefined,
        }}
      />
    </>
  );
}

export default function CommunityPage() {
  const { slug = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const view = (params.get('view') as View) || 'posts';
  const q = useCommunity(slug);
  useDocumentTitle(q.data ? `${q.data.name} (c/${q.data.slug})` : `c/${slug}`);

  if (q.isPending) {
    return (
      <PageColumns>
        <Skeleton className="mb-4 h-48 sm:rounded-lg" />
        <PageSpinner />
      </PageColumns>
    );
  }
  if (q.isError) {
    const notFound = q.error instanceof ApiError && q.error.status === 404;
    return (
      <PageColumns>
        {notFound ? (
          <EmptyState icon={UsersRound} title={`c/${slug} doesn’t exist`} description="It may have been renamed or never created." action={<Link to="/communities" className={buttonClasses('primary', 'sm')}>Browse communities</Link>} />
        ) : (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        )}
      </PageColumns>
    );
  }
  const c = q.data;
  const rail = (
    <>
      <AboutCard c={c} />
      {c.viewer.canView && view !== 'pulse' && <CommunityPulseRail c={c} />}
      <RulesCard c={c} />
      <ModeratorsCard c={c} />
    </>
  );

  return (
    <PageColumns rail={rail}>
      <Header c={c} />
      {!c.viewer.canView ? (
        <Card className="mx-4 sm:mx-0">
          {c.status === 'BANNED' ? (
            <EmptyState icon={Ban} title="This community has been banned" description="Its posts and discussions are no longer available." />
          ) : (
            <EmptyState icon={Lock} title="This community is private" description="Only members can see its posts. Request access by joining if the moderators allow it." />
          )}
        </Card>
      ) : (
        <>
          <div className="mb-4 px-3 sm:px-0 lg:hidden">
            <Segmented<View>
              label="Community sections"
              value={view}
              onChange={(v) => {
                const n = new URLSearchParams(params);
                n.set('view', v);
                setParams(n, { replace: true });
              }}
              options={[
                { value: 'posts', label: 'Posts' },
                { value: 'pulse', label: 'Pulse' },
                { value: 'podcasts', label: 'Podcasts' },
                { value: 'funding', label: 'Funding' },
                { value: 'about', label: 'About' },
                { value: 'members', label: 'Members' },
              ]}
            />
          </div>
          <div className="mb-4 hidden px-3 sm:px-0 lg:block">
            <Segmented<View>
              label="Community sections"
              value={view === 'about' ? 'posts' : view}
              onChange={(v) => {
                const n = new URLSearchParams(params);
                n.set('view', v);
                setParams(n, { replace: true });
              }}
              options={[
                { value: 'posts', label: 'Posts' },
                { value: 'pulse', label: 'Pulse' },
                { value: 'podcasts', label: 'Podcasts' },
                { value: 'funding', label: 'Funding' },
                { value: 'members', label: 'Members' },
              ]}
            />
          </div>
          {view === 'podcasts' && <CommunityPodcasts c={c} />}
          {view === 'posts' && <Posts c={c} />}
          {view === 'pulse' && <PulseSection c={c} />}
          {view === 'funding' && <FundingSection c={c} />}
          {view === 'about' && <div className="space-y-4 px-4 sm:px-0 lg:hidden">{rail}</div>}
          {view === 'about' && <div className="hidden lg:block"><Posts c={c} /></div>}
          {view === 'members' && (
            <Card className="sm:mx-0">
              <MembersList slug={c.slug} canManageRoles={c.viewer.role === 'OWNER' || c.viewer.role === 'ADMIN'} />
            </Card>
          )}
        </>
      )}
    </PageColumns>
  );
}
